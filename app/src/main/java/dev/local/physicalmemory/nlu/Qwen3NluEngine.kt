package dev.local.physicalmemory.nlu

import java.time.LocalDate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Qwen3NluEngine(private val runtimeFactory: () -> NluRuntime, private val grammar: String,
    private val thinking: Boolean = false, private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val observer: (String, String, NluMetrics, String?) -> Unit = { _, _, _, _ -> }) : NluEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    @Volatile private var runtime: NluRuntime? = null
    @Volatile private var released = false
    private val mutableMetrics = MutableStateFlow<NluMetrics?>(null)
    override val metrics = mutableMetrics.asStateFlow()

    override suspend fun warmUp() = withContext(Dispatchers.Default) { mutex.withLock { load() }; Unit }
    private fun load(): Long {
        check(!released) { "NLU 引擎已释放" }
        if(runtime != null) return 0
        val start = clock(); runtime = runtimeFactory(); return clock() - start
    }
    override suspend fun parse(text: String, currentDate: LocalDate): Result<NluResult> = withContext(Dispatchers.Default) {
        mutex.withLock {
            require(text.isNotBlank() && text.length <= 512)
            val started = clock()
            val reused = runtime != null
            try {
                val loadMs = load()
                currentCoroutineContext().ensureActive()
                val output = checkNotNull(runtime).generate(NluPrompt.build(text, currentDate, thinking), grammar, thinking)
                currentCoroutineContext().ensureActive()
                check(!released)
                val t = output.timings
                val m = NluMetrics(loadMs, t[0], t[1], t[2], t[3], t[4], clock()-started, reused, thinking, t.getOrElse(5) { 0 })
                mutableMetrics.value = m
                val raw = if(thinking) output.text.substringAfter("</think>", "").trim() else output.text.trim()
                val result = runCatching { check(output.completed) { "本地解析达到长度或时间限制，请缩短输入重试" }; NluCodec.decode(raw) }
                runCatching { observer(text, raw, m, result.exceptionOrNull()?.message) }
                result
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { Result.failure(e) }
        }
    }
    override fun cancel() { runtime?.cancel() }
    override fun release() {
        released = true
        cancel()
        scope.launch { mutex.withLock { runtime?.close(); runtime = null }; scope.cancel() }
    }
}
