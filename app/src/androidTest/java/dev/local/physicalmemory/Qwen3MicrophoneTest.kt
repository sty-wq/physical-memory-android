package dev.local.physicalmemory

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.voice.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Explicit acoustic test. Saves Debug WAVs; intentionally never creates a business ViewModel. */
@RunWith(AndroidJUnit4::class)
class Qwen3MicrophoneTest {
    @Test fun manualStopProducesFinalAndSavesReplayableWave() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("qwenMic") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        val repetitions = (args.getString("repetitions")?.toIntOrNull() ?: 1).coerceIn(1,10)
        val output = JSONArray()
        val loads = AtomicInteger()
        val closed = AtomicInteger()
        val microphoneClosed = AtomicInteger()
        val input = Qwen3AsrSpeechInput(
            decoderFactory = {
                loads.incrementAndGet()
                val runtime = Qwen3Runtime(Qwen3Model.directory(context))
                object : OfflineAsrDecoder by runtime { override fun close() { runtime.close(); closed.incrementAndGet() } }
            },
            recorderFactory = {
                val recorder = AndroidPcmRecorder(context)
                object : PcmRecorder by recorder { override fun close() { recorder.close(); microphoneClosed.incrementAndGet() } }
            },
            debugAllowed = true,
            saveAudio = DebugWavStore(File(context.filesDir, "asr_debug"))::save,
            memory = ::processMemory,
            sink = (context.applicationContext as MemoryApplication).asrLog::record,
        )
        input.setDebugAudioEnabled(true)
        try {
            repeat(repetitions) { index ->
                val id = "QWEN-MIC-${System.currentTimeMillis()}-$index"
                input.startListening(id)
                waitUntil { input.state.value is SpeechRecognitionState.Listening || input.state.value is SpeechRecognitionState.Error }
                assertTrue(input.state.value.toString(), input.state.value is SpeechRecognitionState.Listening)
                android.util.Log.i("Qwen3MicProbe", "recording-round=${index + 1}")
                Thread.sleep(8_000)
                input.stopListening()
                waitUntil { input.state.value is SpeechRecognitionState.Final || input.state.value is SpeechRecognitionState.Error }
                val metrics = checkNotNull(input.metrics.value)
                output.put(JSONObject().apply {
                    put("sessionId", id); put("round", index + 1); put("state", input.state.value.toString())
                    put("text", metrics.resultText ?: JSONObject.NULL); put("error", metrics.error ?: JSONObject.NULL)
                    put("modelLoadMs", metrics.modelLoadMs); put("modelReused", metrics.modelReused)
                    put("recordDurationMs", metrics.recordDurationMs); put("decodeMs", metrics.decodeMs)
                    put("totalAfterSpeechMs", metrics.totalAfterSpeechMs); put("rtf", metrics.rtf)
                    put("wavPath", metrics.debugWavPath); put("sampledPeakPssKb", metrics.memoryPeakDecode?.pssKb)
                })
                File(context.filesDir, "qwen3-microphone-probe.json").writeText(output.toString(2))
                assertTrue(input.state.value.toString(), input.state.value is SpeechRecognitionState.Final)
                assertTrue(File(checkNotNull(metrics.debugWavPath)).length() > 44)
            }
            assertEquals(1, loads.get())
            assertEquals(repetitions, microphoneClosed.get())
        } finally { input.release(); waitUntil { closed.get() == 1 || loads.get() == 0 } }
    }
    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 60_000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
        assertTrue("Timed out waiting for Qwen3 engine", condition())
    }
}
