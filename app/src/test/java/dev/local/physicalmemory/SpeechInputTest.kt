package dev.local.physicalmemory

import dev.local.physicalmemory.voice.*
import org.junit.Assert.*
import org.junit.Test

class SpeechInputTest {
    @Test fun fakeTraversesStatesAndOnlyOneFinalWins() {
        var time = 100L
        val input = FakeSpeechInput(clock = { time })
        assertEquals(SpeechRecognitionState.Idle, input.state.value)
        input.startListening("one")
        assertTrue(input.state.value is SpeechRecognitionState.Listening)
        time = 250
        input.emitPartial("钥匙")
        assertEquals(150L, input.metrics.value?.firstPartialLatency)
        time = 600
        input.emitFinal("钥匙在哪")
        input.emitFinal("钥匙在别处")
        assertEquals(SpeechRecognitionState.Final("one", "钥匙在哪"), input.state.value)
        assertEquals(500L, input.metrics.value?.finalLatency)
        assertNull(input.metrics.value?.speechEndToFinalLatency)
        input.release(); input.release()
        input.startListening("two")
        assertEquals(1, input.releaseCount)
        assertEquals(SpeechRecognitionState.Idle, input.state.value)
    }
    @Test fun cancelDropsLatePartialAndFinalAndErrorAllowsRestart() {
        val input = FakeSpeechInput()
        input.startListening("one"); input.cancel()
        input.emitPartial("late"); input.emitFinal("late")
        assertEquals(SpeechRecognitionState.Idle, input.state.value)
        assertEquals("CANCELLED", input.metrics.value?.error)
        input.startListening("two"); input.emitError()
        assertTrue(input.state.value is SpeechRecognitionState.Error)
        input.startListening("three"); input.emitFinal("护照在哪")
        assertEquals("three", input.state.value.sessionId)
    }
    @Test fun errorMappingsAreReadableAndRawValuesCanBePreserved() {
        assertEquals("NO_MATCH", SystemSpeechErrors.describe(7).first)
        assertEquals("NO_SPEECH", SystemSpeechErrors.describe(6).first)
        assertEquals("NETWORK", SystemSpeechErrors.describe(2).first)
        assertEquals("INSUFFICIENT_PERMISSIONS", SystemSpeechErrors.describe(9).first)
        assertEquals("RECOGNIZER_BUSY", SystemSpeechErrors.describe(8).first)
        assertEquals("UNKNOWN", SystemSpeechErrors.describe(999).first)
        for (code in 1..15) assertFalse(SystemSpeechErrors.describe(code).second.contains("ERROR_"))
    }
    @Test fun latencyDefinitionsPreserveUnobservedBoundaries() {
        val metrics = AsrMetrics(SpeechEngine.SHERPA, "id", "test", 100,
            recordingStartedAt = 200, speechStartedAt = 300, firstPartialAt = 400,
            finalResultAt = 1000, speechEndedAt = 600, speechBoundarySource = "token_timestamp_estimate")
        assertEquals(100L, metrics.startupLatency)
        assertEquals(300L, metrics.firstPartialLatency)
        assertEquals(900L, metrics.finalLatency)
        assertEquals(400L, metrics.speechEndToFinalLatency)
    }
}
