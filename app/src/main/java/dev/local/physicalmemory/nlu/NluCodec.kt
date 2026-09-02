package dev.local.physicalmemory.nlu

import kotlinx.serialization.json.*

/** Strict shape validation complements grammar (including non-native test inputs). Never repair JSON. */
object NluCodec {
    private val json = Json { isLenient = false; allowSpecialFloatingPointValues = false }
    fun decode(raw: String): NluResult {
        require(raw.length <= 12000) { "NLU 输出过长" }
        val o = json.parseToJsonElement(raw).jsonObject
        require(o.string("schema_version") == "1.0")
        val action = o.string("action")
        val fields = when(action) {
            "UPSERT_ITEM_INFO" -> setOf("item", "location")
            "PROPOSE_ADD_UNITS" -> setOf("item", "count", "unit_label", "location", "default_expiry")
            "OPEN_ITEM" -> setOf("item")
            "UNKNOWN" -> emptySet()
            else -> error("不支持的 NLU action")
        }
        require(o.keys == fields + setOf("schema_version", "action", "issues"))
        val issues = o.getValue("issues").jsonArray.map {
            require(it.jsonPrimitive.isString); Issue.valueOf(it.jsonPrimitive.content)
        }
        require(issues.size <= 8 && issues.distinct().size == issues.size)
        return when(action) {
            "UPSERT_ITEM_INFO" -> {
                val l = o.getValue("location").jsonObject
                require(l.keys == setOf("op", "value"))
                val op = LocationOp.valueOf(l.string("op"))
                val value = l.nullableString("value", 200)
                require(if(op == LocationOp.KEEP) value == null else !value.isNullOrEmpty())
                NluResult.UpsertItemInfo(o.nullableString("item", 80), LocationChange(op, value), issues)
            }
            "PROPOSE_ADD_UNITS" -> {
                val c = o.getValue("count")
                val count = if(c == JsonNull) null else {
                    require(!c.jsonPrimitive.isString)
                    require(c.toString().matches(Regex("-?(0|[1-9][0-9]*)")))
                    c.jsonPrimitive.int
                }
                val expiry = o.getValue("default_expiry").let { e ->
                    if(e == JsonNull) null else {
                        val obj = e.jsonObject
                        require(obj.keys == setOf("value", "source_text"))
                        val value = obj.nullableString("value", 10)
                        require(value == null || value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")))
                        DefaultExpiry(value, obj.nullableString("source_text", 80))
                    }
                }
                NluResult.ProposeAddUnits(o.nullableString("item", 80), count, o.nullableString("unit_label", 16),
                    o.nullableString("location", 200), expiry, issues)
            }
            "OPEN_ITEM" -> NluResult.OpenItem(o.nullableString("item", 80), issues)
            else -> NluResult.Unknown(issues)
        }
    }
    private fun JsonObject.string(key: String): String {
        val p = getValue(key).jsonPrimitive
        require(p.isString); return p.content
    }
    private fun JsonObject.nullableString(key: String, max: Int): String? {
        if(getValue(key) == JsonNull) return null
        return string(key).also { require(it.codePointCount(0, it.length) <= max) }
    }
}
