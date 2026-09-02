package dev.local.physicalmemory.voice

import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import dev.local.physicalmemory.domain.parser.Command

/** Local developer telemetry only: no raw audio and no network transport. */
class AsrLog(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "asr").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    fun record(metrics: AsrMetrics) = write("session", JSONObject().apply {
        put("engine", metrics.engine.name); put("mode", metrics.mode); put("sessionId", metrics.sessionId)
        put("startRequestedAt", metrics.startRequestedAt)
        putNullable("recordingStartedAt", metrics.recordingStartedAt)
        putNullable("speechStartedAt", metrics.speechStartedAt)
        putNullable("firstPartialAt", metrics.firstPartialAt); putNullable("finalResultAt", metrics.finalResultAt)
        putNullable("speechEndedAt", metrics.speechEndedAt); putNullable("speechBoundarySource", metrics.speechBoundarySource)
        putNullable("startupLatency", metrics.startupLatency); putNullable("firstPartialLatency", metrics.firstPartialLatency)
        putNullable("finalLatency", metrics.finalLatency); putNullable("speechEndToFinalLatency", metrics.speechEndToFinalLatency)
        putNullable("modelLoadMs", metrics.modelLoadMs); putNullable("modelLoadedPssKb", metrics.modelLoadedPssKb)
        putNullable("resultText", metrics.resultText)
        putNullable("error", metrics.error); putNullable("rawErrorCode", metrics.rawErrorCode)
        putNullable("cancelledAt", metrics.cancelledAt); put("audioSamples", metrics.audioSamples)
        putNullable("peakRmsDb", metrics.peakRmsDb)
        putNullable("modelReused", metrics.modelReused); putNullable("recordDurationMs", metrics.recordDurationMs)
        putNullable("recordingStoppedAt", metrics.recordingStoppedAt); putNullable("decodeMs", metrics.decodeMs)
        putNullable("totalAfterSpeechMs", metrics.totalAfterSpeechMs); putNullable("rtf", metrics.rtf)
        putNullable("memoryBeforeLoad", metrics.memoryBeforeLoad?.json())
        putNullable("memoryAfterLoad", metrics.memoryAfterLoad?.json())
        putNullable("memoryPeakDecode", metrics.memoryPeakDecode?.json())
        putNullable("memoryAfterDecode", metrics.memoryAfterDecode?.json())
        putNullable("debugWavPath", metrics.debugWavPath); putNullable("debugAudioError", metrics.debugAudioError)
        putNullable("endReason", metrics.endReason)
        putNullable("captureBufferBytes", metrics.captureBufferBytes); putNullable("errorDetail", metrics.errorDetail)
    })
    fun probe(value: SystemSpeechCapability) = write("capability", JSONObject().apply {
        put("systemAvailable", value.available); put("onDeviceAvailable", value.onDeviceAvailable)
        put("services", org.json.JSONArray(value.services)); putNullable("defaultProvider", value.defaultProvider)
        put("manufacturer", value.manufacturer); put("model", value.model)
        put("androidVersion", value.androidVersion); put("sdk", value.sdk)
    })
    fun command(sessionId: String?, text: String, parsed: Command, outcome: String) {
        if (sessionId == null) return // Benchmark traces belong only to speech sessions.
        write("command", JSONObject().apply {
            put("sessionId", sessionId); put("inputText", text); put("outcome", outcome)
            when (parsed) {
                is Command.Store -> { put("command", "STORE"); put("item", parsed.item); put("location", parsed.location) }
                is Command.Find -> { put("command", "FIND"); put("item", parsed.item); put("location", JSONObject.NULL) }
                Command.Unknown -> { put("command", "UNKNOWN"); put("item", JSONObject.NULL); put("location", JSONObject.NULL) }
            }
        })
    }
    private fun write(type: String, value: JSONObject) {
        scope.launch {
            try {
                value.put("type", type); value.put("wallClockMs", System.currentTimeMillis())
                value.put("processPssKb", Debug.getPss())
                val line = value.toString()
                Log.i("PhysicalMemoryASR", line)
                mutex.withLock { File(directory, "events.jsonl").appendText(line + "\n") }
            } catch (error: Exception) { Log.w("PhysicalMemoryASR", "Local telemetry write failed", error) }
        }
    }
    private fun JSONObject.putNullable(key: String, value: Any?) { put(key, value ?: JSONObject.NULL) }
    private fun ProcessMemory.json() = JSONObject().apply {
        put("pssKb", pssKb); put("rssKb", rssKb); put("nativeHeapKb", nativeHeapKb); put("javaHeapKb", javaHeapKb)
    }
}
