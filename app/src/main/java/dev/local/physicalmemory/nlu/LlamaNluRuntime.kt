package dev.local.physicalmemory.nlu

import java.io.File

/** This adapter is the only Kotlin class aware of JNI. One serialized engine owns one handle. */
class LlamaNluRuntime(model: File, threads: Int = 4) : NluRuntime {
    private val lock = Any()
    @Volatile private var handle: Long
    init {
        require(model.isFile && model.length() == MODEL_BYTES) { "缺少完整的 Qwen3-1.7B Q8_0 模型，请通过开发部署脚本安装" }
        handle = nativeLoad(model.absolutePath.toByteArray(), threads.coerceIn(1, 8))
        check(handle != 0L) { "Qwen3-1.7B 模型加载失败" }
    }
    override fun generate(prompt: String, grammar: String, thinking: Boolean): NativeOutput {
        check(handle != 0L)
        return nativeGenerate(handle, prompt.toByteArray(), grammar.toByteArray(), thinking)
    }
    override fun cancel() = synchronized(lock) { if(handle != 0L) nativeCancel(handle) }
    override fun close() = synchronized(lock) { if(handle != 0L) { nativeFree(handle); handle = 0L } }
    private external fun nativeLoad(path: ByteArray, threads: Int): Long
    private external fun nativeGenerate(handle: Long, prompt: ByteArray, grammar: ByteArray, thinking: Boolean): NativeOutput
    private external fun nativeCancel(handle: Long)
    private external fun nativeFree(handle: Long)
    companion object {
        const val MODEL_FILE = "Qwen3-1.7B-Q8_0.gguf"
        const val MODEL_BYTES = 1834426016L
        const val MODEL_SHA256 = "061b54daade076b5d3362dac252678d17da8c68f07560be70818cace6590cb1a"
        init { System.loadLibrary("physical_nlu") }
    }
}
