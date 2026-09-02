package dev.local.physicalmemory.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat

data class SystemSpeechCapability(
    val available: Boolean,
    val onDeviceAvailable: Boolean,
    val services: List<String>,
    val defaultProvider: String?,
    val manufacturer: String = Build.MANUFACTURER,
    val model: String = Build.MODEL,
    val androidVersion: String = Build.VERSION.RELEASE,
    val sdk: Int = Build.VERSION.SDK_INT,
) {
    val description: String get() = if (available || onDeviceAvailable)
        "系统服务：可用；设备端：${if (onDeviceAvailable) "可用" else "未检测到"}"
    else "当前设备没有可用的系统语音识别服务"

    companion object {
        fun probe(context: Context): SystemSpeechCapability {
            val app = context.applicationContext
            return SystemSpeechCapability(
                available = runCatching { SpeechRecognizer.isRecognitionAvailable(app) }.getOrDefault(false),
                onDeviceAvailable = Build.VERSION.SDK_INT >= 31 &&
                    runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(app) }.getOrDefault(false),
                services = runCatching {
                    @Suppress("DEPRECATION")
                    app.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), PackageManager.GET_META_DATA)
                        .map { "${it.serviceInfo.packageName}/${it.serviceInfo.name}" }
                }.getOrDefault(emptyList()),
                defaultProvider = runCatching { Settings.Secure.getString(app.contentResolver, "voice_recognition_service") }.getOrNull(),
            )
        }
    }
}

class AndroidSpeechInput(
    context: Context,
    val capability: SystemSpeechCapability = SystemSpeechCapability.probe(context),
    sink: (AsrMetrics) -> Unit = {},
) : SessionSpeechInput(
    SpeechEngine.SYSTEM,
    SpeechAvailability(capability.available || capability.onDeviceAvailable, capability.description,
        if (capability.onDeviceAvailable) "SYSTEM_ON_DEVICE" else "SYSTEM_SERVICE"),
    sink = sink,
) {
    private val app = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var timeout: Runnable? = null

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    override fun startListening(sessionId: String) = onMain {
        if (!begin(sessionId)) return@onMain
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            fail(sessionId, "INSUFFICIENT_PERMISSIONS", "需要麦克风权限，仍可使用文本输入", 9)
            return@onMain
        }
        if (!availability.value.available) {
            fail(sessionId, "SERVICE_UNAVAILABLE", capability.description)
            return@onMain
        }
        try {
            var mode = "SYSTEM_SERVICE"
            val onDevice = if (Build.VERSION.SDK_INT >= 31 && capability.onDeviceAvailable) {
                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(app) }.getOrNull()
            } else null
            recognizer = if (onDevice != null) {
                mode = "SYSTEM_ON_DEVICE"
                onDevice
            } else if (capability.available) SpeechRecognizer.createSpeechRecognizer(app)
            else throw IllegalStateException("No recognizer")
            mutableAvailability.value = availability.value.copy(mode = mode)
            updateMetrics(sessionId) { it.copy(mode = mode) }
            recognizer!!.setRecognitionListener(listener(sessionId))
            recognizer!!.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
            timeout = Runnable {
                if (activeSession == sessionId) {
                    fail(sessionId, "SESSION_TIMEOUT", "识别等待超时，请重试或使用文本输入")
                    destroyRecognizer()
                }
            }.also { handler.postDelayed(it, 45_000) }
        } catch (_: SecurityException) {
            fail(sessionId, "INSUFFICIENT_PERMISSIONS", "无法使用麦克风，请检查权限", 9)
            destroyRecognizer()
        } catch (_: Exception) {
            fail(sessionId, "SERVICE_UNAVAILABLE", "系统语音服务启动失败，可切换本地 Sherpa 或文本输入")
            destroyRecognizer()
        }
    }

    private fun listener(id: String) = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listening(id)
        override fun onBeginningOfSpeech() {
            updateMetrics(id) { it.copy(speechStartedAt = it.speechStartedAt ?: clock(), speechBoundarySource = "system_callbacks") }
        }
        override fun onRmsChanged(rmsdB: Float) {
            // Provider-reported level is useful for diagnostics, not a speech-boundary detector.
            if (rmsdB.isFinite()) updateMetrics(id) {
                if (it.peakRmsDb == null || rmsdB > it.peakRmsDb) it.copy(peakRmsDb = rmsdB) else it
            }
        }
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) {
            partial(id, partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
        }
        override fun onEndOfSpeech() {
            updateMetrics(id) { it.copy(speechEndedAt = clock(), speechBoundarySource = "system_callbacks") }
            finalizing(id)
        }
        override fun onResults(results: Bundle?) {
            if (id != activeSession) return
            finish(id, results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty())
            destroyRecognizer()
        }
        override fun onError(error: Int) {
            if (id != activeSession) return
            val mapped = SystemSpeechErrors.describe(error)
            fail(id, mapped.first, mapped.second, error)
            destroyRecognizer()
        }
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    override fun stopListening() = onMain {
        activeSession?.let { id ->
            finalizing(id)
            runCatching { recognizer?.stopListening() }.onFailure {
                fail(id, "CLIENT", "停止识别失败，请重新尝试"); destroyRecognizer()
            }
        }
    }

    override fun cancel() = onMain { invalidate(); destroyRecognizer() }
    override fun release() = onMain { if (!released) { markReleased(); cancel() } }

    private fun destroyRecognizer() {
        timeout?.let { handler.removeCallbacks(it) }
        timeout = null
        val old = recognizer
        recognizer = null
        runCatching { old?.cancel() }
        runCatching { old?.destroy() }
    }
}
