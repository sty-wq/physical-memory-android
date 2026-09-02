package dev.local.physicalmemory.domain.model

data class ItemRecord(
    val id: Long,
    val name: String,
    val location: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Name-only retrieval projection; matching never invents a location. */
data class ItemName(val id: Long, val name: String)

/** Preserve Latin word boundaries; redundant spacing between Chinese characters is ignored. */
fun normalizeItemText(text: String): String = text
    .replace(Regex("[\\s\\u3000]+"), " ")
    .trim()
    .replace(Regex("(?<=\\p{IsHan}) +(?=\\p{IsHan})"), "")

const val MAX_ITEM_LENGTH = 80
const val MAX_LOCATION_LENGTH = 200
