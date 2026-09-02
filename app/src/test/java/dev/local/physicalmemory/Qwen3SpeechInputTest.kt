package dev.local.physicalmemory

import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalCoroutinesApi::class)
class Qwen3SpeechInputTest {
    @get:Rule val temp = TemporaryFolder()
    private class Recorder(private val onRead: () -> Unit, private val zero: Boolean = false) : PcmRecorder {
        var closed = 0
        var started = 0
        override fun start() { started++ }
        override fun read(buffer: ShortArray): Int { buffer.fill(if (zero) 0 else 1000); onRead(); return buffer.size }
        override fun stop() {}
        override fun close() { closed++ }
    }
    private class Decoder(private val result: () -> String = { "钥匙在哪" }) : OfflineAsrDecoder {
        var calls = 0
        var closed = 0
        override fun decode(samples: FloatArray, sampleRate: Int): String {
            assertEquals(16_000, sampleRate); assertTrue(samples.isNotEmpty()); calls++
            return result()
        }
        override fun close() { closed++ }
    }

    @Test fun listeningThenRecognizingThenOneFinalWithoutPartials() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        val seen = mutableListOf<String>()
        val finals = mutableListOf<AsrMetrics>()
        val recorder = Recorder({ assertTrue(input.state.value is SpeechRecognitionState.Listening); seen += "listening"; input.stopListening() })
        val decoder = Decoder { assertTrue(input.state.value is SpeechRecognitionState.Recognizing); seen += "recognizing"; "钥匙在哪" }
        input = Qwen3AsrSpeechInput({ decoder }, { recorder }, dispatcher = StandardTestDispatcher(testScheduler), sink = finals::add)
        input.startListening("one"); advanceUntilIdle()
        assertEquals(listOf("listening", "recognizing"), seen)
        assertEquals(SpeechRecognitionState.Final("one", "钥匙在哪"), input.state.value)
        assertEquals(1, recorder.closed); assertEquals(1, finals.size)
        assertNull(input.metrics.value?.firstPartialAt)
        assertEquals(100L, input.metrics.value?.recordDurationMs)
        input.stopListening(); assertEquals(1, finals.size)
        input.release(); advanceUntilIdle(); assertEquals(1, decoder.closed)
    }
    @Test fun tenUtterancesReuseOneModelAndReleaseEachRecorder() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        var loads = 0
        val decoder = Decoder()
        val recordings = mutableListOf<Recorder>()
        input = Qwen3AsrSpeechInput({ loads++; decoder }, { Recorder({ input.stopListening() }).also(recordings::add) },
            dispatcher = StandardTestDispatcher(testScheduler))
        repeat(10) { i -> input.startListening("session-$i"); advanceUntilIdle(); assertTrue(input.state.value is SpeechRecognitionState.Final) }
        assertEquals(1, loads); assertEquals(10, decoder.calls); assertTrue(recordings.all { it.closed == 1 })
        assertEquals(true, input.metrics.value?.modelReused); assertEquals(0L, input.metrics.value?.modelLoadMs)
        input.release(); input.release(); advanceUntilIdle(); assertEquals(1, decoder.closed)
    }
    @Test fun modelLoadErrorDoesNotOpenMicrophoneAndRetryCanWork() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        var loads = 0; var opens = 0
        input = Qwen3AsrSpeechInput({ if (++loads == 1) error("bad model") else Decoder() },
            { opens++; Recorder({ input.stopListening() }) }, dispatcher = StandardTestDispatcher(testScheduler))
        input.startListening("one"); advanceUntilIdle()
        assertEquals("MODEL_LOAD", (input.state.value as SpeechRecognitionState.Error).code); assertEquals(0, opens)
        input.startListening("two"); advanceUntilIdle(); assertTrue(input.state.value is SpeechRecognitionState.Final)
        input.release(); advanceUntilIdle()
    }
    @Test fun microphoneAndPermissionErrorsAreDistinct() = runTest {
        listOf(IllegalStateException("mic unavailable") to "MICROPHONE", SecurityException() to "INSUFFICIENT_PERMISSIONS").forEach { (error, expected) ->
            val input = Qwen3AsrSpeechInput({ Decoder() }, { throw error }, dispatcher = StandardTestDispatcher(testScheduler))
            input.startListening("one"); advanceUntilIdle()
            assertEquals(expected, (input.state.value as SpeechRecognitionState.Error).code)
            input.release(); advanceUntilIdle()
        }
    }
    @Test fun decodeErrorClosesMicrophoneAndKeepsModelReusable() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        val recorder = Recorder({ input.stopListening() })
        val decoder = Decoder { error("decode failed") }
        input = Qwen3AsrSpeechInput({ decoder }, { recorder }, dispatcher = StandardTestDispatcher(testScheduler))
        input.startListening("one"); advanceUntilIdle()
        assertEquals("DECODE", (input.state.value as SpeechRecognitionState.Error).code)
        assertEquals(1, recorder.closed); assertEquals(0, decoder.closed)
        input.release(); advanceUntilIdle()
    }
    @Test fun cancelRecordingDoesNotDecodeAndCanResume() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        var cancel = true
        val decoder = Decoder()
        input = Qwen3AsrSpeechInput({ decoder }, { Recorder({ if (cancel) input.cancel() else input.stopListening() }) },
            dispatcher = StandardTestDispatcher(testScheduler))
        input.startListening("one"); advanceUntilIdle(); assertEquals(0, decoder.calls)
        assertEquals(SpeechRecognitionState.Idle, input.state.value)
        cancel = false; input.startListening("two"); advanceUntilIdle(); assertEquals(1, decoder.calls)
        input.release(); advanceUntilIdle()
    }
    @Test fun cancelAndReleaseDuringNativeDecodeDropLateFinalAndCloseAfterReturn() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        lateinit var decoder: Decoder
        decoder = Decoder { input.cancel(); input.release(); assertEquals(0, decoder.closed); "钥匙放在错误位置" }
        input = Qwen3AsrSpeechInput({ decoder }, { Recorder({ input.stopListening() }) }, dispatcher = StandardTestDispatcher(testScheduler))
        input.startListening("one"); advanceUntilIdle()
        assertEquals(SpeechRecognitionState.Idle, input.state.value); assertEquals(1, decoder.closed)
        input.startListening("after-release"); advanceUntilIdle(); assertEquals(1, decoder.calls)
    }
    @Test fun stopDuringLoadingNeverStartsMicrophoneOrLeavesInitializingStuck() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        input = Qwen3AsrSpeechInput({ input.stopListening(); Decoder() }, { error("must not open") }, dispatcher = StandardTestDispatcher(testScheduler))
        input.startListening("one"); advanceUntilIdle()
        assertEquals("NO_SPEECH", (input.state.value as SpeechRecognitionState.Error).code)
        input.release(); advanceUntilIdle()
    }
    @Test fun zeroSamplesSkipDecodeAndDebugAudioRequiresExplicitOptIn() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        var saves = 0
        val decoder = Decoder()
        input = Qwen3AsrSpeechInput({ decoder }, { Recorder({ input.stopListening() }, zero = true) },
            saveAudio = { _, _ -> saves++; "test.wav" }, debugAllowed = true, dispatcher = StandardTestDispatcher(testScheduler))
        input.startListening("one"); advanceUntilIdle(); assertEquals(0, saves)
        assertEquals("NO_AUDIO_INPUT", (input.state.value as SpeechRecognitionState.Error).code)
        input.setDebugAudioEnabled(true); input.startListening("two"); advanceUntilIdle()
        assertEquals(1, saves); assertEquals(0, decoder.calls)
        input.release(); advanceUntilIdle()
    }
    @Test fun productModeCannotEnableRawAudioSaving() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        input = Qwen3AsrSpeechInput({ Decoder() }, { Recorder({ input.stopListening() }) },
            saveAudio = { _, _ -> error("must not save") }, debugAllowed = false, dispatcher = StandardTestDispatcher(testScheduler))
        input.setDebugAudioEnabled(true); input.startListening("one"); advanceUntilIdle()
        assertTrue(input.state.value is SpeechRecognitionState.Final); assertNull(input.metrics.value?.debugWavPath)
        input.release(); advanceUntilIdle()
    }
    @Test fun debugWavPreservesSignedPcmAndHeader() {
        val pcm = shortArrayOf(-32768, -100, 0, 100, 32767)
        val path = DebugWavStore(temp.root).save("ASR-one", pcm)
        val bytes = java.io.File(path).readBytes()
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4)); assertEquals(16_000, data.getInt(24))
        assertEquals(pcm.size * 2, data.getInt(40))
        pcm.indices.forEach { assertEquals(pcm[it], data.getShort(44 + it * 2)) }
    }
    @Test fun cancelledAudioIsNeitherSavedNorDecodedEvenWithDebugOptIn() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        var saves=0;val decoder=Decoder()
        val recorder=Recorder({input.cancel()})
        input=Qwen3AsrSpeechInput({decoder},{recorder},saveAudio={_,_->saves++;"bad.wav"},debugAllowed=true,
            dispatcher=StandardTestDispatcher(testScheduler),minimumRecordDurationMs=400,requireExplicitStop=true)
        input.setDebugAudioEnabled(true);input.startListening("cancel");advanceUntilIdle()
        assertEquals(0,saves);assertEquals(0,decoder.calls);assertEquals(1,recorder.closed)
        input.release();advanceUntilIdle()
    }
    @Test fun tooShortHoldNeverInvokesDecoderOrSavesAudio() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        val decoder=Decoder();var saves=0
        input=Qwen3AsrSpeechInput({decoder},{Recorder({input.stopListening()})},saveAudio={_,_->saves++;"bad.wav"},debugAllowed=true,
            dispatcher=StandardTestDispatcher(testScheduler),minimumRecordDurationMs=400,requireExplicitStop=true)
        input.setDebugAudioEnabled(true);input.startListening("short");advanceUntilIdle()
        assertEquals("TOO_SHORT",(input.state.value as SpeechRecognitionState.Error).code)
        assertEquals(0,decoder.calls);assertEquals(0,saves)
        input.release();advanceUntilIdle()
    }
    @Test fun thirtySecondLimitWhileStillHeldDiscardsInsteadOfAutoDecoding() = runTest {
        val decoder=Decoder();val recorder=Recorder({})
        val input=Qwen3AsrSpeechInput({decoder},{recorder},dispatcher=StandardTestDispatcher(testScheduler),requireExplicitStop=true)
        input.startListening("limit");advanceUntilIdle()
        assertEquals("HOLD_LIMIT",(input.state.value as SpeechRecognitionState.Error).code)
        assertEquals(0,decoder.calls);assertEquals(1,recorder.closed)
        input.release();advanceUntilIdle()
    }

}
