package dev.local.physicalmemory

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import dev.local.physicalmemory.voice.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Explicit real-model probe; no microphone, UI command submission, or Room access. */
@RunWith(AndroidJUnit4::class)
class Qwen3NativeTest {
    @Test fun officialWaveAndRepeatedStreams() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("qwenNative") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = Qwen3Model.directory(context)
        val wave = WaveReader.readWaveFromFile(File(directory, "test_wavs/raokouling.wav").absolutePath)
        val repetitions = (args.getString("repetitions")?.toIntOrNull() ?: 1).coerceIn(1, 10)
        val results = JSONArray()
        val report = File(context.filesDir, "qwen3-native-probe.json")
        val before = processMemory()
        val started = SystemClock.elapsedRealtime()
        Qwen3Runtime(directory).use { runtime ->
            val loaded = SystemClock.elapsedRealtime()
            val afterLoad = processMemory()
            repeat(repetitions) { index ->
                var peak = afterLoad
                val running = AtomicBoolean(true)
                val monitor = thread(name = "qwen-memory-sampler") {
                    while (running.get()) {
                        val value = processMemory()
                        peak = ProcessMemory(maxOf(peak.pssKb, value.pssKb), maxOf(peak.rssKb, value.rssKb),
                            maxOf(peak.nativeHeapKb, value.nativeHeapKb), maxOf(peak.javaHeapKb, value.javaHeapKb))
                        Thread.sleep(100)
                    }
                }
                val decodeStart = SystemClock.elapsedRealtime()
                val text: String
                var decodeMs: Long
                try { text = runtime.decode(wave.samples, wave.sampleRate) }
                finally { decodeMs = SystemClock.elapsedRealtime() - decodeStart; running.set(false); monitor.join() }
                val audioMs = wave.samples.size * 1000L / wave.sampleRate
                results.put(JSONObject().apply {
                    put("round", index + 1); put("inputKind", "official_wav_direct_android_kotlin")
                    put("modelLoadMs", loaded - started); put("modelReused", index > 0)
                    put("audioDurationMs", audioMs); put("decodeMs", decodeMs); put("rtf", decodeMs.toDouble() / audioMs)
                    put("text", text); put("beforeLoad", memoryJson(before)); put("afterLoad", memoryJson(afterLoad))
                    put("sampledPeak", memoryJson(peak)); put("afterDecode", memoryJson(processMemory()))
                })
                report.writeText(results.toString(2))
                assertTrue("Expected a non-empty Qwen3-ASR result", text.isNotBlank())
            }
        }
        File(context.filesDir, "qwen3-native-after-release.json").writeText(memoryJson(processMemory()).toString(2))
    }
    private fun memoryJson(value: ProcessMemory) = JSONObject().apply {
        put("pssKb", value.pssKb); put("rssKb", value.rssKb)
        put("nativeHeapKb", value.nativeHeapKb); put("javaHeapKb", value.javaHeapKb)
    }
}
