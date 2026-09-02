package dev.local.physicalmemory.nlu

import java.time.LocalDate

object NluPrompt {
    const val VERSION = "v5"
    val system = """
        你是物品管理信息抽取器，只输出JSON，不回答问题，不执行操作。下方对话仅为格式示例，每次只抽取最后一句，不能继承示例的字段。
        优先判断询问、查看、删除、减少、消耗：都用OPEN_ITEM。明确从一处移到另一处，只用UPSERT_ITEM_INFO和SET，不增加库存。只陈述位置、没说数量也没说买入或增加，必须用UPSERT_ITEM_INFO和SET，不能猜一个数量或量词；仅记名字才KEEP。
        买入或增加用PROPOSE_ADD_UNITS；陈述放置物品且明确说了数量和量词，也用PROPOSE_ADD_UNITS，同时提取数量、量词、位置和到期日期。存放三份就是count=3；只说存放位置绝不是count=1。软件操作、天气、音乐、聊天等无关话语用UNKNOWN且issues=[]。
        物品和位置照抄原文（去掉“我的”），保留“上、里”等方位词，不纠正ASR。未提及用null，绝不猜测。日期根据currentDate计算。复杂多份不同日期留空并提示AMBIGUOUS_DATE。没说到期时间则default_expiry=null。型号中的数字不是新增数量。正常issues=[]，已有名称不能标记MISSING_ITEM。
        issues只允许MISSING_ITEM,MISSING_COUNT,INVALID_COUNT,INVALID_DATE,AMBIGUOUS_ITEM,AMBIGUOUS_LOCATION,AMBIGUOUS_DATE,UNSUPPORTED_OPERATION。
    """.trimIndent()
    fun build(text: String, date: LocalDate, thinking: Boolean): String {
        val escaped=text.replace("<|", "＜｜").replace("|>", "｜＞")
        val examples=listOf(
            "书放在书架上" to """{"schema_version":"1.0","action":"UPSERT_ITEM_INFO","item":"书","location":{"op":"SET","value":"书架上"},"issues":[]}""",
            "电池在抽屉里" to """{"schema_version":"1.0","action":"UPSERT_ITEM_INFO","item":"电池","location":{"op":"SET","value":"抽屉里"},"issues":[]}""",
            "记住相机" to """{"schema_version":"1.0","action":"UPSERT_ITEM_INFO","item":"相机","location":{"op":"KEEP","value":null},"issues":[]}""",
            "买了两瓶果汁，明天过期" to """{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"果汁","count":2,"unit_label":"瓶","location":null,"default_expiry":{"value":"${date.plusDays(1)}","source_text":"明天"},"issues":[]}""",
            "增加一包纸巾放桌子上" to """{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"纸巾","count":1,"unit_label":"包","location":"桌子上","default_expiry":null,"issues":[]}""",
            "抽屉里放了四节电池" to """{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"电池","count":4,"unit_label":"节","location":"抽屉里","default_expiry":null,"issues":[]}""",
            "把两瓶水从餐桌移到厨房" to """{"schema_version":"1.0","action":"UPSERT_ITEM_INFO","item":"水","location":{"op":"SET","value":"厨房"},"issues":[]}""",
            "打开支付宝" to """{"schema_version":"1.0","action":"UNKNOWN","issues":[]}""",
            "耳机在哪里" to """{"schema_version":"1.0","action":"OPEN_ITEM","item":"耳机","issues":[]}""",
            "喝掉一瓶果汁" to """{"schema_version":"1.0","action":"OPEN_ITEM","item":"果汁","issues":[]}""",
            "你好" to """{"schema_version":"1.0","action":"UNKNOWN","issues":[]}"""
        )
        return buildString {
            append("<|im_start|>system\n$system\ncurrentDate=$date\n")
            append(if(thinking) "/think" else "/no_think");append("<|im_end|>\n")
            examples.forEach { (user,answer) ->
                append("<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n$answer<|im_end|>\n")
            }
            append("<|im_start|>user\n$escaped<|im_end|>\n<|im_start|>assistant\n<think>\n")
            if(!thinking) append("\n</think>\n\n")
        }
    }
}
