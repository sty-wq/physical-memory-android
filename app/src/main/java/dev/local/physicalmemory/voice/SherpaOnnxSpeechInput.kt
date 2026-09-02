package dev.local.physicalmemory.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class SherpaOnnxSpeechInput(context: Context, sink: (AsrMetrics) -> Unit = {}) :
    SessionSpeechInput(SpeechEngine.SHERPA, SpeechAvailability(true, "模型已内置，首次说话时加载", "SHERPA_CPU_INT8"), sink = sink) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var capture: Capture? = null

    private class Capture {
        @Volatile var cancelled = false
        @Volatile var stopped = false
        var recorder: AudioRecord? = null
        @Synchronized fun stop(cancel: Boolean) {
            if (cancel) cancelled = true
            stopped = true
            runCatching { recorder?.stop() } // Unblocks AudioRecord.read; worker owns release().
        }
        @Synchronized fun start(value: AudioRecord): Boolean {
            recorder = value
            if (stopped) return false
            value.startRecording()
            return true
        }
        @Synchronized fun close() {
            runCatching { recorder?.stop() }
            recorder?.release()
            recorder = null
        }
    }

    override fun startListening(sessionId: String) {
        if (!begin(sessionId)) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(sessionId, "INSUFFICIENT_PERMISSIONS", "需要麦克风权限，仍可使用文本输入", 9)
            return
        }
        val current = Capture()
        capture = current
        mutableAvailability.value = availability.value.copy(detail = "正在加载模型")
        scope.launch { recognize(sessionId, current) }
    }

    @Suppress("MissingPermission") // Checked above and caught again if revoked during initialization.
    private fun recognize(id: String, current: Capture) {
        var answer: String? = null
        var failure: Pair<String, String>? = null
        try {
            val initStart = clock()
            SherpaStreamingSession(app.assets).use { session ->
                val loadedMs = clock() - initStart
                val loadedPss = android.os.Debug.getPss()
                updateMetrics(id) { it.copy(modelLoadMs = loadedMs, modelLoadedPssKb = loadedPss) }
                mutableAvailability.value = availability.value.copy(detail = "模型加载成功")
                if (current.stopped || id != activeSession) return@use
                val sampleRate = SherpaStreamingSession.SAMPLE_RATE
                val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0) { "Unsupported audio format" }
                val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minimum * 2, 6_400))
                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    recorder.release()
                    throw IllegalStateException("Microphone initialization failed")
                }
                if (!current.start(recorder)) return@use
                check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                listening(id)
                val started = clock()
                val buffer = ShortArray(1_600) // 100 ms, mono signed 16-bit PCM, same as official Android sample.
                while (!current.stopped && id == activeSession) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (current.stopped || id != activeSession) break
                    check(read > 0) { "AudioRecord.read failed: $read" }
                    session.accept(FloatArray(read) { buffer[it] / 32768f })
                    updateMetrics(id) { it.copy(audioSamples = it.audioSamples + read) }
                    val result = session.result()
                    if (result.text.isNotBlank()) {
                        partial(id, result.text.trim())
                        // These are token emission estimates, NOT measured acoustic speech boundaries.
                        val times = result.timestamps
                        if (times.isNotEmpty()) updateMetrics(id) { metrics ->
                            val recording = metrics.recordingStartedAt ?: started
                            metrics.copy(
                                speechStartedAt = recording + (times.first() * 1_000).toLong(),
                                speechEndedAt = recording + (times.last() * 1_000).toLong(),
                                speechBoundarySource = "token_timestamp_estimate",
                            )
                        }
                    }
                    if (session.isEndpoint()) { answer = result.text.trim(); break }
                    // Defensive ceiling if a backend never yields an endpoint. Native endpoint is normally <=20 s.
                    if (clock() - started > 30_000) { failure = "SESSION_TIMEOUT" to "录音超时，请分成短句重试"; break }
                }
                if (!current.cancelled && id == activeSession && failure == null && answer == null) {
                    finalizing(id)
                    answer = session.finishInput().text.trim()
                }
            }
        } catch (_: SecurityException) {
            failure = "INSUFFICIENT_PERMISSIONS" to "无法使用麦克风，请检查权限"
        } catch (_: LinkageError) {
            mutableAvailability.value = availability.value.copy(available = false, detail = "本地引擎加载失败")
            failure = "NATIVE_LIBRARY" to "本地引擎加载失败，请使用文本输入"
        } catch (error: Exception) {
            failure = if (metrics.value?.modelLoadMs == null) "MODEL_LOAD" to "模型加载失败，请重试或使用文本输入"
                else "AUDIO" to "录音或识别失败，请检查麦克风后重试"
            android.util.Log.e("PhysicalMemoryASR", "Sherpa session failed: $id", error)
        } finally {
            current.close()
            if (capture === current) capture = null
        }
        // Native objects and microphone are closed BEFORE publishing a terminal result/new session.
        if (!current.cancelled && id == activeSession) {
            if (failure != null) {
                mutableAvailability.value = availability.value.copy(detail = failure.second)
                fail(id, failure.first, failure.second)
            } else {
                mutableAvailability.value = availability.value.copy(detail = "就绪（录音资源已释放）")
                finish(id, answer.orEmpty())
            }
        }
    }

    override fun stopListening() {
        activeSession?.let { finalizing(it) }
        capture?.stop(cancel = false)
    }
    override fun cancel() {
        invalidate()
        capture?.stop(cancel = true)
    }
    override fun release() {
        if (!released) { markReleased(); cancel(); scope.cancel() }
    }
}
