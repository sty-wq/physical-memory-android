package dev.local.physicalmemory.voice

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig

/** One native stream per utterance; called exclusively by its IO worker. */
class SherpaStreamingSession(assets: AssetManager) : AutoCloseable {
    private val recognizer = OnlineRecognizer(assets, OnlineRecognizerConfig(
        modelConfig = OnlineModelConfig(
            zipformer2Ctc = OnlineZipformer2CtcModelConfig(model = "asr/model.int8.onnx"),
            tokens = "asr/tokens.txt", numThreads = 2, provider = "cpu",
        ),
        // Official Kotlin sample defaults: 2.4 s initial silence, 1.4 s trailing silence, 20 s utterance.
        endpointConfig = EndpointConfig(),
        enableEndpoint = true,
        decodingMethod = "greedy_search",
    ))
    private val stream = try { recognizer.createStream() }
        catch (error: Throwable) { recognizer.release(); throw error }
    private var closed = false

    fun accept(samples: FloatArray) {
        stream.acceptWaveform(samples, SAMPLE_RATE)
        decodeAvailable()
    }
    private fun decodeAvailable() { while (recognizer.isReady(stream)) recognizer.decode(stream) }
    fun result(): OnlineRecognizerResult = recognizer.getResult(stream)
    fun isEndpoint(): Boolean = recognizer.isEndpoint(stream)
    fun finishInput(): OnlineRecognizerResult {
        // Flush right context for a user-requested stop/file end; endpoint decisions remain native.
        stream.acceptWaveform(FloatArray(SAMPLE_RATE), SAMPLE_RATE)
        stream.inputFinished()
        decodeAvailable()
        return result()
    }
    override fun close() {
        if (!closed) { closed = true; stream.release(); recognizer.release() }
    }
    companion object { const val SAMPLE_RATE = 16_000 }
}
