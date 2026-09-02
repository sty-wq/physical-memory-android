package dev.local.physicalmemory.voice

/** Deterministic test adapter. Never opens a microphone or impersonates real ASR measurements. */
class FakeSpeechInput(engine: SpeechEngine = SpeechEngine.SYSTEM, clock: () -> Long = { System.nanoTime() / 1_000_000 }) :
    SessionSpeechInput(engine, SpeechAvailability(true, "Fake 测试输入", "FAKE"), clock) {
    var releaseCount = 0
        private set
    override fun startListening(sessionId: String) { if (begin(sessionId)) listening(sessionId) }
    fun emitPartial(text: String) { activeSession?.let { partial(it, text) } }
    fun emitFinal(text: String) { activeSession?.let { finish(it, text) } }
    fun emitError() { activeSession?.let { fail(it, "FAKE_ERROR", "测试识别错误") } }
    override fun stopListening() { activeSession?.let { finalizing(it) } }
    override fun cancel() = invalidate()
    override fun release() { if (!released) { cancel(); markReleased(); releaseCount++ } }
}
