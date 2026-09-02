package dev.local.physicalmemory.voice

import kotlinx.coroutines.flow.StateFlow

enum class SpeechEngine(val label: String) { SYSTEM("系统"), SHERPA("旧 Sherpa"), QWEN3_ASR("Qwen3-ASR") }

sealed interface SpeechRecognitionState {
    val sessionId: String? get() = null
    data object Idle : SpeechRecognitionState
    data class Initializing(override val sessionId: String) : SpeechRecognitionState
    data class Listening(override val sessionId: String) : SpeechRecognitionState
    data class Partial(override val sessionId: String, val text: String) : SpeechRecognitionState
    data class Finalizing(override val sessionId: String) : SpeechRecognitionState
    data class Recognizing(override val sessionId: String) : SpeechRecognitionState
    data class Final(override val sessionId: String, val text: String) : SpeechRecognitionState
    data class Error(override val sessionId: String, val code: String, val message: String, val rawCode: Int? = null) : SpeechRecognitionState
}

val SpeechRecognitionState.isActive: Boolean get() = this is SpeechRecognitionState.Initializing ||
    this is SpeechRecognitionState.Listening || this is SpeechRecognitionState.Partial || this is SpeechRecognitionState.Finalizing ||
    this is SpeechRecognitionState.Recognizing

data class SpeechAvailability(val available: Boolean, val detail: String, val mode: String)

interface SpeechInput {
    val state: StateFlow<SpeechRecognitionState>
    val metrics: StateFlow<AsrMetrics?>
    val availability: StateFlow<SpeechAvailability>
    fun startListening(sessionId: String)
    fun stopListening()
    fun cancel()
    fun release()
    suspend fun warmUp() {}
    fun setDebugAudioEnabled(enabled: Boolean) {}
}
