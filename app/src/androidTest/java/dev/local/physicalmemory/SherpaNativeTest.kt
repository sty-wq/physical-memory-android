package dev.local.physicalmemory

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.voice.SherpaStreamingSession
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Real ARM64 inference using an official WAV; no microphone or writes to the item database. */
@RunWith(AndroidJUnit4::class)
class SherpaNativeTest {
    @Test fun officialWaveLoadsDecodesPartialAndFinalAndReleasesRepeatedly() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.context.assets.open("asr-fixture/official-0.wav").use { it.readBytes() }
        val wav = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        var index = 12
        var pcmOffset = 0
        var pcmSize = 0
        while (index + 8 <= bytes.size) {
            val type = String(bytes, index, 4)
            val size = wav.getInt(index + 4)
            if (type == "fmt ") {
                assertEquals(1, wav.getShort(index + 8).toInt())
                assertEquals(1, wav.getShort(index + 10).toInt())
                assertEquals(16_000, wav.getInt(index + 12))
                assertEquals(16, wav.getShort(index + 22).toInt())
            }
            if (type == "data") { pcmOffset = index + 8; pcmSize = size; break }
            index += 8 + size + size % 2
        }
        assertTrue(pcmSize > 0)
        val samples = FloatArray(pcmSize / 2) { wav.getShort(pcmOffset + it * 2) / 32768f }
        val runs = org.json.JSONArray()
        repeat(2) {
            val before = SystemClock.elapsedRealtime()
            SherpaStreamingSession(instrumentation.targetContext.assets).use { session ->
                val loaded = SystemClock.elapsedRealtime()
                val partials = mutableSetOf<String>()
                var endpoint = false
                var offset = 0
                while (offset < samples.size) {
                    session.accept(samples.copyOfRange(offset, minOf(offset + 1600, samples.size)))
                    session.result().text.takeIf { it.isNotBlank() }?.let(partials::add)
                    endpoint = endpoint || session.isEndpoint()
                    offset += 1600
                }
                repeat(20) {
                    session.accept(FloatArray(1600))
                    endpoint = endpoint || session.isEndpoint()
                }
                val result = session.finishInput()
                assertTrue("Expected streaming partials", partials.size > 1)
                assertTrue("Expected non-empty final", result.text.isNotBlank())
                assertTrue("Native endpoint should detect trailing silence", endpoint)
                runs.put(JSONObject().apply {
                    put("modelLoadMs", loaded - before)
                    put("decodeMs", SystemClock.elapsedRealtime() - loaded)
                    put("audioDurationMs", samples.size * 1000 / 16_000)
                    put("partialCount", partials.size); put("resultText", result.text)
                    put("timestampCount", result.timestamps.size); put("endpointDetected", endpoint)
                    put("processPssKbWhileLoaded", Debug.getPss()); put("inputKind", "official_wav_not_microphone")
                })
            }
        }
        File(instrumentation.targetContext.getExternalFilesDir(null), "sherpa-native-probe.json").writeText(runs.toString(2))
    }
}
