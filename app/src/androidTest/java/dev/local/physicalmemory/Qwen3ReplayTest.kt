package dev.local.physicalmemory

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import com.k2fsa.sherpa.onnx.VersionInfo
import dev.local.physicalmemory.voice.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Same saved 16 kHz WAV -> both engines; never submits commands or touches Room. */
@RunWith(AndroidJUnit4::class)
class Qwen3ReplayTest {
    @Test fun replayIdenticalPcmThroughBothModels() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("qwenReplay") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = Qwen3Model.directory(context)
        val paths = args.getString("replayFiles")?.split(',')?.map { name ->
            require(name.matches(Regex("[A-Za-z0-9_-]+\\.wav")))
            File(context.filesDir, "asr_debug/$name")
        } ?: listOf(File(model, "test_wavs/noise2.wav"))
        require(paths.size in 1..20)
        val results = JSONArray()
        Qwen3Runtime(model).use { qwen ->
            paths.forEach { file ->
                val wave = WaveReader.readWaveFromFile(file.absolutePath)
                assertEquals("Compare the same native 16 kHz PCM without different resamplers", 16_000, wave.sampleRate)
                val started = SystemClock.elapsedRealtime()
                val qwenText = qwen.decode(wave.samples, wave.sampleRate)
                val qwenMs = SystemClock.elapsedRealtime() - started
                val oldText: String
                val oldMs: Long
                SherpaStreamingSession(context.assets).use { old ->
                    val began = SystemClock.elapsedRealtime()
                    var offset = 0
                    while (offset < wave.samples.size) {
                        old.accept(wave.samples.copyOfRange(offset, minOf(offset + 1600, wave.samples.size)))
                        offset += 1600
                    }
                    oldText = old.finishInput().text
                    oldMs = SystemClock.elapsedRealtime() - began
                }
                results.put(JSONObject().apply {
                    put("sherpaVersion", VersionInfo.version); put("sherpaGitSha", VersionInfo.gitSha1)
                    put("onnxruntimeVersion", VersionInfo.onnxruntimeVersion)
                    put("file", file.name); put("inputKind", if (args.getString("replayFiles") == null) "official_wav" else "saved_microphone_wav")
                    put("audioMs", wave.samples.size * 1000L / wave.sampleRate)
                    put("qwenText", qwenText); put("qwenDecodeMs", qwenMs)
                    put("oldText", oldText); put("oldDecodeMs", oldMs)
                })
                File(context.filesDir, "qwen3-replay.json").writeText(results.toString(2))
                assertTrue(qwenText.isNotBlank())
            }
        }
    }
}
