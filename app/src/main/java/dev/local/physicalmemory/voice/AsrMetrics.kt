package dev.local.physicalmemory.voice

/** All timestamps use a monotonic millisecond clock, never wall clock. Null means unobserved. */
data class AsrMetrics(
    val engine: SpeechEngine,
    val sessionId: String,
    val mode: String,
    val startRequestedAt: Long,
    val recordingStartedAt: Long? = null,
    val speechStartedAt: Long? = null,
    val firstPartialAt: Long? = null,
    val finalResultAt: Long? = null,
    val speechEndedAt: Long? = null,
    val speechBoundarySource: String? = null,
    val modelLoadMs: Long? = null,
    val modelLoadedPssKb: Long? = null,
    val resultText: String? = null,
    val error: String? = null,
    val rawErrorCode: Int? = null,
    val cancelledAt: Long? = null,
    val audioSamples: Long = 0,
    val peakRmsDb: Float? = null,
    val modelReused: Boolean? = null,
    val recordDurationMs: Long? = null,
    val recordingStoppedAt: Long? = null,
    val decodeMs: Long? = null,
    val memoryBeforeLoad: ProcessMemory? = null,
    val memoryAfterLoad: ProcessMemory? = null,
    val memoryPeakDecode: ProcessMemory? = null,
    val memoryAfterDecode: ProcessMemory? = null,
    val debugWavPath: String? = null,
    val debugAudioError: String? = null,
    val endReason: String? = null,
    val captureBufferBytes: Int? = null,
    val errorDetail: String? = null,
) {
    val startupLatency: Long? get() = recordingStartedAt?.minus(startRequestedAt)
    val firstPartialLatency: Long? get() = firstPartialAt?.minus(startRequestedAt)
    val finalLatency: Long? get() = finalResultAt?.minus(startRequestedAt)
    val speechEndToFinalLatency: Long? get() = speechEndedAt?.let { end -> finalResultAt?.minus(end) }
    // For manual stop this is stop-to-final, not an acoustic speech-end measurement.
    val totalAfterSpeechMs: Long? get() = recordingStoppedAt?.let { finalResultAt?.minus(it) }
    val rtf: Double? get() = recordDurationMs?.takeIf { it > 0 }?.let { duration -> decodeMs?.toDouble()?.div(duration) }
}
