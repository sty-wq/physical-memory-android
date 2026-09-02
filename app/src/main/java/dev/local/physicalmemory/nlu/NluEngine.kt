package dev.local.physicalmemory.nlu

import java.time.LocalDate
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

data class NluMetrics(val modelLoadMs: Long = 0, val promptTokens: Long = 0, val prefillMs: Long = 0,
    val ttftMs: Long = 0, val decodeMs: Long = 0, val generatedTokens: Long = 0, val totalNluMs: Long = 0,
    val reused: Boolean = false, val thinking: Boolean = false, val cachedPromptTokens: Long = 0)

interface NluEngine {
    val metrics: StateFlow<NluMetrics?>
    suspend fun parse(text: String, currentDate: LocalDate): Result<NluResult>
    suspend fun warmUp()
    fun cancel() {}
    fun release()
}

/** Explicit fixture engine. Production never silently falls back to a fake or a rule parser. */
class FakeNluEngine(private val respond: (String, LocalDate) -> NluResult) : NluEngine {
    override val metrics = MutableStateFlow<NluMetrics?>(null)
    var calls = 0; private set
    override suspend fun parse(text: String, currentDate: LocalDate): Result<NluResult> = runCatching { calls++; respond(text, currentDate) }
    override suspend fun warmUp() {}
    override fun release() {}
}

interface NluRuntime : AutoCloseable {
    fun generate(prompt: String, grammar: String, thinking: Boolean): NativeOutput
    fun cancel()
}

data class NativeOutput(val bytes: ByteArray, val timings: LongArray, val completed: Boolean) {
    val text get() = bytes.toString(Charsets.UTF_8)
}
