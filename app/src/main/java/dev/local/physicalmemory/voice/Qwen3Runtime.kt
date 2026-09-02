package dev.local.physicalmemory.voice

import android.content.Context
import android.os.Debug
import com.k2fsa.sherpa.onnx.*
import java.io.File

interface OfflineAsrDecoder : AutoCloseable {
    fun decode(samples: FloatArray, sampleRate: Int): String
}

object Qwen3Model {
    const val ID = "sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25"
    const val SAMPLE_RATE = 16_000
    val requiredFiles = listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx",
        "tokenizer/vocab.json", "tokenizer/merges.txt", "tokenizer/tokenizer_config.json")
    fun directory(context: Context): File {
        val internal = File(context.filesDir, "asr_models/$ID")
        // Prefer app-owned files on phones whose scoped external storage rejects adb-created files.
        // Retain the existing emulator deployment of the same model without copying another 1 GB.
        return if (internal.exists()) internal
        else File(requireNotNull(context.getExternalFilesDir(null)), "asr_models/$ID")
    }
    fun validate(directory: File) {
        require(requiredFiles.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }) {
            "Qwen3-ASR model incomplete: $directory"
        }
        require(File(directory, ".verified").isFile) { "Model deployment has not completed checksum verification" }
    }
}

/** Own on a serialized worker. Stream is per utterance; recognizer is reused until close. */
class Qwen3Runtime(directory: File) : OfflineAsrDecoder {
    private var recognizer: OfflineRecognizer?
    init {
        Qwen3Model.validate(directory)
        recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                qwen3Asr = OfflineQwen3AsrModelConfig(
                    convFrontend = File(directory, "conv_frontend.onnx").absolutePath,
                    encoder = File(directory, "encoder.int8.onnx").absolutePath,
                    decoder = File(directory, "decoder.int8.onnx").absolutePath,
                    tokenizer = File(directory, "tokenizer").absolutePath,
                    maxTotalLen = 512, maxNewTokens = 128,
                    temperature = 1e-6f, topP = 0.8f, seed = 42, hotwords = "",
                ),
                tokens = "", // v1.13.7 Kotlin example type 61: tokenizer owns the vocabulary.
                provider = "cpu", numThreads = 2, debug = false,
            ), hotwordsFile = "",
        ))
    }
    override fun decode(samples: FloatArray, sampleRate: Int): String {
        val owner = checkNotNull(recognizer) { "Recognizer released" }
        val stream = owner.createStream()
        try {
            check(stream.ptr != 0L) { "Native stream creation failed" }
            stream.acceptWaveform(samples, sampleRate)
            owner.decode(stream)
            return owner.getResult(stream).text.trim()
        } finally { stream.release() }
    }
    override fun close() { recognizer?.release(); recognizer = null }
}

data class ProcessMemory(val pssKb: Long, val rssKb: Long, val nativeHeapKb: Long, val javaHeapKb: Long)
fun processMemory(): ProcessMemory {
    val runtime = Runtime.getRuntime()
    val rss = runCatching { File("/proc/self/status").useLines { lines ->
        lines.first { it.startsWith("VmRSS:") }.split(Regex("\\s+"))[1].toLong()
    } }.getOrDefault(0)
    return ProcessMemory(Debug.getPss(), rss, Debug.getNativeHeapAllocatedSize() / 1024,
        (runtime.totalMemory() - runtime.freeMemory()) / 1024)
}
