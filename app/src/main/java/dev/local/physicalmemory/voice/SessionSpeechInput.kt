package dev.local.physicalmemory.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Guards callbacks from cancelled/old sessions and logs exactly one terminal outcome. */
abstract class SessionSpeechInput(
    private val engine: SpeechEngine,
    initialAvailability: SpeechAvailability,
    protected val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sink: (AsrMetrics) -> Unit = {},
) : SpeechInput {
    protected val mutableAvailability = MutableStateFlow(initialAvailability)
    final override val availability = mutableAvailability.asStateFlow()
    private val mutableState = MutableStateFlow<SpeechRecognitionState>(SpeechRecognitionState.Idle)
    final override val state = mutableState.asStateFlow()
    private val mutableMetrics = MutableStateFlow<AsrMetrics?>(null)
    final override val metrics = mutableMetrics.asStateFlow()
    @Volatile protected var activeSession: String? = null
        private set
    @Volatile protected var released = false
        private set

    @Synchronized protected fun begin(id: String): Boolean {
        if (released || activeSession != null) return false
        activeSession = id
        mutableMetrics.value = AsrMetrics(engine, id, availability.value.mode, clock())
        mutableState.value = SpeechRecognitionState.Initializing(id)
        return true
    }

    @Synchronized protected fun updateMetrics(id: String, transform: (AsrMetrics) -> AsrMetrics) {
        if (id == activeSession) mutableMetrics.value = mutableMetrics.value?.let(transform)
    }

    @Synchronized protected fun listening(id: String) {
        if (id != activeSession) return
        updateMetrics(id) { it.copy(recordingStartedAt = it.recordingStartedAt ?: clock()) }
        mutableState.value = SpeechRecognitionState.Listening(id)
    }

    @Synchronized protected fun partial(id: String, text: String) {
        if (id != activeSession || text.isBlank()) return
        updateMetrics(id) { it.copy(firstPartialAt = it.firstPartialAt ?: clock()) }
        mutableState.value = SpeechRecognitionState.Partial(id, text)
    }

    @Synchronized protected fun finalizing(id: String) {
        if (id == activeSession) mutableState.value = SpeechRecognitionState.Finalizing(id)
    }

    @Synchronized protected fun recognizing(id: String) {
        if (id == activeSession) mutableState.value = SpeechRecognitionState.Recognizing(id)
    }

    @Synchronized protected fun finish(id: String, text: String) {
        if (id != activeSession) return
        if (text.isBlank()) { fail(id, "NO_MATCH", "没有识别到内容，请再试一次"); return }
        updateMetrics(id) { it.copy(finalResultAt = clock(), resultText = text) }
        activeSession = null
        mutableState.value = SpeechRecognitionState.Final(id, text)
        report()
    }

    @Synchronized protected fun fail(id: String, code: String, message: String, rawCode: Int? = null) {
        if (id != activeSession) return
        updateMetrics(id) { it.copy(error = code, rawErrorCode = rawCode) }
        activeSession = null
        mutableState.value = SpeechRecognitionState.Error(id, code, message, rawCode)
        report()
    }

    @Synchronized protected fun invalidate() {
        if (activeSession != null) {
            mutableMetrics.value = mutableMetrics.value?.copy(cancelledAt = clock(), error = "CANCELLED")
            activeSession = null
            report()
        }
        mutableState.value = SpeechRecognitionState.Idle
    }

    @Synchronized protected fun markReleased() { released = true }
    private fun report() { mutableMetrics.value?.let { runCatching { sink(it) } } }
}
