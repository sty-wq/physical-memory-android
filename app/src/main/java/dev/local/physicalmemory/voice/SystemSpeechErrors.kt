package dev.local.physicalmemory.voice

/** Android's public error values; pure Kotlin so error behavior can be tested without a device. */
object SystemSpeechErrors {
    fun describe(code: Int): Pair<String, String> = when (code) {
        1 -> "NETWORK_TIMEOUT" to "系统语音服务网络超时，可切换本地 Sherpa"
        2 -> "NETWORK" to "系统语音服务网络异常，可切换本地 Sherpa"
        3 -> "AUDIO" to "麦克风录音失败，请检查是否被其他应用占用"
        4, 11 -> "SERVER" to "系统语音服务异常，请稍后重试"
        5 -> "CLIENT" to "识别已中断，请重试"
        6 -> "NO_SPEECH" to "没有听到说话，请再试一次"
        7 -> "NO_MATCH" to "没有识别到内容，请再试一次"
        8 -> "RECOGNIZER_BUSY" to "语音服务正忙，请稍后重试"
        9 -> "INSUFFICIENT_PERMISSIONS" to "需要麦克风权限，仍可使用文本输入"
        10 -> "TOO_MANY_REQUESTS" to "请求过于频繁，请稍后重试"
        12 -> "LANGUAGE_NOT_SUPPORTED" to "系统服务不支持中文，可切换本地 Sherpa"
        13 -> "LANGUAGE_UNAVAILABLE" to "系统中文识别模型不可用，可切换本地 Sherpa"
        14, 15 -> "SERVICE_UNAVAILABLE" to "系统识别服务无法检查或下载模型，可切换本地 Sherpa"
        else -> "UNKNOWN" to "语音识别失败，请重试或使用文本输入"
    }
}
