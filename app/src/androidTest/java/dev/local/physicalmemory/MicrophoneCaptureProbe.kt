package dev.local.physicalmemory

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.voice.SherpaStreamingSession
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/** Explicitly opt-in hardware diagnostic. Exports signal statistics only, never raw audio or item writes. */
@RunWith(AndroidJUnit4::class)
class MicrophoneCaptureProbe {
    @Test fun repeatedOpenReadClose() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("microphoneProbe") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO))
        val observations = JSONArray()
        val decode = InstrumentationRegistry.getArguments().getString("microphoneDecode") == "true"
        repeat(3) { index ->
            val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6400))
            var samples = 0L
            var nonzero = 0L
            var peak = 0
            var squared = 0.0
            var decoder: SherpaStreamingSession? = null
            var resultText: String? = null
            val start = SystemClock.elapsedRealtime()
            try {
                if (decode) decoder = SherpaStreamingSession(context.assets)
                assertEquals(AudioRecord.STATE_INITIALIZED, recorder.state)
                recorder.startRecording()
                android.util.Log.i("PhysicalMemoryMicProbe", "recording-round=${index + 1}")
                val buffer = ShortArray(1600)
                repeat(if (decode) 80 else 15) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    assertTrue("AudioRecord.read must return samples", read > 0)
                    decoder?.accept(FloatArray(read) { buffer[it] / 32768f })
                    samples += read
                    repeat(read) { i ->
                        val value = buffer[i].toInt()
                        if (value != 0) nonzero++
                        peak = maxOf(peak, abs(value))
                        squared += value.toDouble() * value
                    }
                }
                resultText = decoder?.finishInput()?.text
            } finally {
                runCatching { recorder.stop() }
                recorder.release()
                decoder?.close()
            }
            observations.put(JSONObject().apply {
                put("round", index + 1); put("samples", samples); put("nonzeroSamples", nonzero); put("peakPcm", peak)
                put("rmsDbFs", if (squared > 0) 20 * log10(sqrt(squared / samples) / 32768) else JSONObject.NULL)
                put("elapsedMs", SystemClock.elapsedRealtime() - start)
                put("resultText", resultText ?: JSONObject.NULL)
            })
        }
        File(context.getExternalFilesDir(null), "microphone-capture-probe.json").writeText(observations.toString(2))
    }
}
