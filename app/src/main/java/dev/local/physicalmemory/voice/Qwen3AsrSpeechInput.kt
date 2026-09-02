package dev.local.physicalmemory.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

/** Real offline state machine. A single worker lock owns model lifetime and all JNI calls. */
class Qwen3AsrSpeechInput(
    private val decoderFactory: () -> OfflineAsrDecoder,
    private val recorderFactory: () -> PcmRecorder,
    private val saveAudio: (String, ShortArray) -> String? = { _, _ -> null },
    private val debugAllowed: Boolean = false,
    private val memory: () -> ProcessMemory? = { null },
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    clock: () -> Long = { System.nanoTime() / 1_000_000 },
    sink: (AsrMetrics) -> Unit = {},
    private val minimumRecordDurationMs: Long = 0,
    private val requireExplicitStop: Boolean = false,
) : SessionSpeechInput(SpeechEngine.QWEN3_ASR,
    SpeechAvailability(true, "Qwen3-ASR 0.6B INT8 · 点击开始，再次点击停止", "QWEN3_CPU_INT8_AUTO"), clock, sink) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val worker = Mutex()
    private var decoder: OfflineAsrDecoder? = null
    @Volatile private var capture: Capture? = null
    @Volatile private var saveDebugAudio = false

    private class Capture(val id: String, val saveAudio: Boolean) {
        @Volatile var stopped = false
        @Volatile var cancelled = false
        private var recorder: PcmRecorder? = null
        @Synchronized fun attach(value: PcmRecorder): Boolean {
            recorder = value
            if (stopped) return false
            value.start()
            return true
        }
        @Synchronized fun stop(cancel: Boolean) {
            cancelled = cancelled || cancel; stopped = true
            recorder?.stop() // Interrupts a blocking read; worker remains responsible for close.
        }
        @Synchronized fun close() { recorder?.close(); recorder = null }
    }

    override fun setDebugAudioEnabled(enabled: Boolean) { saveDebugAudio = debugAllowed && enabled }
    override suspend fun warmUp() = withContext(Dispatchers.Default) {
        worker.withLock { check(!released); if (decoder == null) decoder = decoderFactory() }
    }
    override fun startListening(sessionId: String) {
        if (!begin(sessionId)) return
        val current = Capture(sessionId, saveDebugAudio)
        capture = current
        scope.launch { worker.withLock { recognize(current) } }
    }
    private suspend fun recognize(current: Capture) {
        val id = current.id
        var stage = "MODEL_LOAD"
        try {
            if (current.stopped || id != activeSession) {
                if (!current.cancelled) fail(id, "NO_SPEECH", "录音尚未开始，请重试")
                return
            }
            val reused = decoder != null
            val before = memory()
            val loading = clock()
            if (decoder == null) {
                mutableAvailability.value = availability.value.copy(detail = "正在加载语音模型…")
                decoder = decoderFactory()
            }
            updateMetrics(id) { it.copy(modelReused = reused, modelLoadMs = if (reused) 0 else clock() - loading,
                memoryBeforeLoad = before, memoryAfterLoad = memory(), modelLoadedPssKb = memory()?.pssKb) }
            mutableAvailability.value = availability.value.copy(detail = "Qwen3-ASR 已加载 · 点击开始，再次点击停止")
            if (current.stopped || id != activeSession) {
                if (!current.cancelled) fail(id, "NO_SPEECH", "录音尚未开始，请重试")
                return
            }
            stage = "MICROPHONE"
            val source = recorderFactory()
            updateMetrics(id) { it.copy(captureBufferBytes = source.bufferSizeBytes) }
            val collected = ShortArray(16_000 * 30) // Bounded utterance, at most 30 seconds.
            var count = 0
            var hasSignal = false
            try {
                if (!current.attach(source)) {
                    if (!current.cancelled) fail(id, "NO_SPEECH", "录音尚未开始，请重试")
                    return
                }
                listening(id)
                val buffer = ShortArray(1_600)
                while (!current.stopped && id == activeSession && count < collected.size) {
                    val read = source.read(buffer)
                    if (read > 0) {
                        val take = minOf(read, collected.size - count)
                        repeat(take) { if (buffer[it] != 0.toShort()) hasSignal = true }
                        buffer.copyInto(collected, count, 0, take); count += take
                        updateMetrics(id) { it.copy(audioSamples = count.toLong(), recordDurationMs = count * 1000L / 16_000) }
                    } else if (!current.stopped) error("AudioRecord.read failed: $read")
                }
            } finally { current.close() }
            updateMetrics(id) { it.copy(recordingStoppedAt = clock(),
                endReason = if (count == collected.size) "MAX_30_SECONDS" else "MANUAL_STOP") }
            // A cancelled/accidental hold must discard PCM before debug persistence or inference.
            if (current.cancelled || id != activeSession) return
            if (requireExplicitStop && count == collected.size && !current.stopped) {
                fail(id, "HOLD_LIMIT", "录音最长 30 秒，请松手后重新录音"); return
            }
            if (count * 1000L / 16_000 < minimumRecordDurationMs) {
                fail(id, "TOO_SHORT", "录音时间太短，请按住再说一次"); return
            }
            if (count > 0 && current.saveAudio) {
                runCatching { saveAudio(id, collected.copyOf(count)) }
                    .onSuccess { path -> updateMetrics(id) { it.copy(debugWavPath = path) } }
                    .onFailure { error -> updateMetrics(id) { it.copy(debugAudioError = error.javaClass.simpleName) } }
            }
            if (current.cancelled || id != activeSession) return
            if (count == 0 || !hasSignal) {
                fail(id, "NO_AUDIO_INPUT", "没有采集到声音，请检查麦克风后重试"); return
            }
            stage = "DECODE"
            recognizing(id)
            val samples = FloatArray(count) { collected[it] / 32768f }
            val peak = AtomicReference(memory())
            val monitor = scope.launch {
                while (isActive) {
                    memory()?.let { next -> peak.updateAndGet { old -> if (old == null) next else ProcessMemory(
                        maxOf(old.pssKb, next.pssKb), maxOf(old.rssKb, next.rssKb),
                        maxOf(old.nativeHeapKb, next.nativeHeapKb), maxOf(old.javaHeapKb, next.javaHeapKb)) } }
                    delay(100)
                }
            }
            val began = clock()
            val text: String
            try { text = checkNotNull(decoder).decode(samples, 16_000) }
            finally {
                val elapsed = clock() - began
                monitor.cancelAndJoin()
                updateMetrics(id) { it.copy(decodeMs = elapsed, memoryPeakDecode = peak.get(), memoryAfterDecode = memory()) }
            }
            if (!current.cancelled) finish(id, text)
        } catch (_: SecurityException) {
            fail(id, "INSUFFICIENT_PERMISSIONS", "无法使用麦克风，请检查权限")
        } catch (_: LinkageError) {
            fail(id, "NATIVE_LIBRARY", "本地语音运行库加载失败")
        } catch (error: Exception) {
            updateMetrics(id) { it.copy(errorDetail = "${error.javaClass.simpleName}: ${error.message.orEmpty().take(500)}") }
            val message = when (stage) {
                "MODEL_LOAD" -> "语音模型加载失败，请确认已完成模型部署"
                "MICROPHONE" -> "录音失败，请检查麦克风后重试"
                else -> "语音识别失败，请重试"
            }
            fail(id, stage, message)
        } finally {
            current.close()
            if (capture === current) capture = null
        }
    }
    override fun stopListening() { capture?.stop(cancel = false) }
    override fun cancel() { invalidate(); capture?.stop(cancel = true) }
    override fun release() {
        if (released) return
        markReleased(); cancel()
        // Synchronous native decode cannot be aborted. Suppress its result, then free under the same lock.
        scope.launch { worker.withLock { decoder?.close(); decoder = null }; scope.cancel() }
    }
}
