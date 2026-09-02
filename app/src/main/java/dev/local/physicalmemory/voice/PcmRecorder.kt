package dev.local.physicalmemory.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

interface PcmRecorder : AutoCloseable {
    val bufferSizeBytes: Int get() = 0
    fun start()
    fun read(buffer: ShortArray): Int
    fun stop()
}

/** Same MIC/16k/mono/PCM16 and buffer sizing as the verified Sherpa capture path. */
class AndroidPcmRecorder(context: Context) : PcmRecorder {
    private val recorder: AudioRecord
    override val bufferSizeBytes: Int
    init {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            throw SecurityException("Microphone permission denied")
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "Unsupported audio format" }
        bufferSizeBytes = maxOf(minimum * 2, 6_400)
        @Suppress("MissingPermission")
        val audio = AudioRecord(MediaRecorder.AudioSource.MIC, 16_000,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSizeBytes)
        if (audio.state != AudioRecord.STATE_INITIALIZED) { audio.release(); error("Microphone initialization failed") }
        recorder = audio
    }
    override fun start() { recorder.startRecording(); check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) }
    override fun read(buffer: ShortArray): Int = recorder.read(buffer, 0, buffer.size)
    override fun stop() { runCatching { recorder.stop() } }
    override fun close() { stop(); recorder.release() }
}
