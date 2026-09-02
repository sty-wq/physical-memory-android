package dev.local.physicalmemory

import androidx.lifecycle.ViewModelStore
import dev.local.physicalmemory.domain.ItemRepository
import dev.local.physicalmemory.domain.PhysicalMemory
import dev.local.physicalmemory.domain.matching.FuzzyItemMatcher
import dev.local.physicalmemory.domain.model.ItemName
import dev.local.physicalmemory.domain.model.ItemRecord
import dev.local.physicalmemory.ui.home.HomeViewModel
import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val system = UnreliableInput()
    private val sherpa = UnreliableInput()
    private val items = MutableStateFlow<List<ItemRecord>>(emptyList())
    private var writes = 0
    private var reads = 0
    private val repository = object : ItemRepository {
        override fun getRecentItems() = items
        override suspend fun getItemNames(): List<ItemName> { reads++; return items.value.map { ItemName(it.id, it.name) } }
        override suspend fun findItem(name: String): ItemRecord? { reads++; return items.value.find { it.name == name } }
        override suspend fun findItemById(id: Long): ItemRecord? { reads++; return items.value.find { it.id == id } }
        override suspend fun upsertItem(name: String, location: String): ItemRecord {
            writes++
            val old = items.value.find { it.name == name }
            val result = ItemRecord(old?.id ?: writes.toLong(), name, location, old?.createdAt ?: writes.toLong(), writes.toLong())
            items.value = items.value.filterNot { it.name == name } + result
            return result
        }
    }
    private lateinit var model: HomeViewModel
    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        model = HomeViewModel(PhysicalMemory(repository, FuzzyItemMatcher(dispatcher)),
            speechInputs = mapOf(SpeechEngine.SYSTEM to system, SpeechEngine.SHERPA to sherpa))
        store.put("home", model)
    }
    @After fun teardown() { store.clear(); Dispatchers.resetMain() }

    @Test fun qwenOfflineFinalUsesExistingStoreAndFindAndDefaultsToQwen() = runTest {
        lateinit var input: Qwen3AsrSpeechInput
        var text = "钥匙放在玄关柜"
        input = Qwen3AsrSpeechInput(
            decoderFactory = { object : OfflineAsrDecoder {
                override fun decode(samples: FloatArray, sampleRate: Int) = text
                override fun close() {}
            } },
            recorderFactory = { object : PcmRecorder {
                override fun start() {}
                override fun read(buffer: ShortArray): Int { buffer.fill(100); input.stopListening(); return buffer.size }
                override fun stop() {}
                override fun close() {}
            } }, dispatcher = dispatcher,
        )
        val qwenModel = HomeViewModel(PhysicalMemory(repository, FuzzyItemMatcher(dispatcher)),
            speechInputs = mapOf(SpeechEngine.SYSTEM to system, SpeechEngine.QWEN3_ASR to input))
        store.put("qwen-home", qwenModel)
        assertEquals(SpeechEngine.QWEN3_ASR, qwenModel.state.value.selectedEngine)
        qwenModel.startSpeech(); advanceUntilIdle()
        assertEquals(1, writes); assertEquals("玄关柜", items.value.single().location)
        text = "钥匙在哪"; qwenModel.startSpeech(); advanceUntilIdle()
        assertEquals("钥匙在玄关柜", qwenModel.state.value.result?.text)
        assertEquals(1, writes)
        store.clear(); advanceUntilIdle()
    }

    @Test fun partialUpdatesTranscriptionButNeverParsesOrTouchesRepository() = runTest {
        model.onInputChanged("已有文字草稿")
        model.startSpeech(); advanceUntilIdle()
        listOf("钥匙", "钥匙放在", "钥匙放在玄关柜", "钥匙在哪").forEach {
            system.emit(SpeechRecognitionState.Partial(system.id, it)); advanceUntilIdle()
            assertEquals(it, model.state.value.transcription)
            assertEquals("已有文字草稿", model.state.value.input)
            assertEquals(0, writes + reads)
            assertNull(model.state.value.result)
        }
    }
    @Test fun finalStoresAndDuplicateOrChangedFinalCannotSubmitTwice() = runTest {
        model.startSpeech(); advanceUntilIdle()
        val id = system.id
        system.emit(SpeechRecognitionState.Final(id, "钥匙放在玄关柜")); advanceUntilIdle()
        assertEquals(1, writes)
        system.emit(SpeechRecognitionState.Final(id, "钥匙放在书桌")); advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals("玄关柜", items.value.single().location)
        assertEquals("", model.state.value.input)
    }
    @Test fun sameWordsInANewSessionExecuteNormallyAndFindIsReadOnly() = runTest {
        repeat(2) {
            model.startSpeech(); advanceUntilIdle()
            system.emit(SpeechRecognitionState.Final(system.id, "钥匙放在玄关柜")); advanceUntilIdle()
        }
        assertEquals(2, writes)
        model.startSpeech(); advanceUntilIdle()
        system.emit(SpeechRecognitionState.Final(system.id, "钥匙在哪")); advanceUntilIdle()
        assertEquals("钥匙在玄关柜", model.state.value.result?.text)
        assertEquals(2, writes)
    }
    @Test fun errorAndPermissionDenialKeepTextWorking() = runTest {
        model.onInputChanged("护照放在抽屉")
        model.startSpeech(); advanceUntilIdle()
        system.emit(SpeechRecognitionState.Error(system.id, "NETWORK", "网络异常", 2)); advanceUntilIdle()
        assertEquals("护照放在抽屉", model.state.value.input)
        model.onPermissionDenied()
        model.submit(); advanceUntilIdle()
        assertEquals("已记住：护照在抽屉", model.state.value.result?.text)
    }
    @Test fun engineSwitchCancelsOldSessionAndIgnoresLateFinal() = runTest {
        model.startSpeech(); advanceUntilIdle()
        val old = system.id
        model.selectEngine(SpeechEngine.SHERPA); advanceUntilIdle()
        assertTrue(system.cancelCount > 0)
        system.emit(SpeechRecognitionState.Final(old, "钥匙放在错误位置"))
        model.startSpeech(); advanceUntilIdle()
        sherpa.emit(SpeechRecognitionState.Final(sherpa.id, "钥匙放在书桌")); advanceUntilIdle()
        assertEquals(1, writes)
        assertEquals("书桌", items.value.single().location)
    }
    @Test fun pageStopAndConfigurationChangeCannotReplayFinal() = runTest {
        model.startSpeech(); advanceUntilIdle()
        val old = system.id
        model.onPageStopped()
        system.emit(SpeechRecognitionState.Final(old, "钥匙放在玄关柜")); advanceUntilIdle()
        assertEquals(0, writes)
        model.startSpeech(); advanceUntilIdle()
        system.emit(SpeechRecognitionState.Final(old, "钥匙放在错误位置")); advanceUntilIdle()
        assertEquals(0, writes)
        system.emit(SpeechRecognitionState.Final(system.id, "钥匙放在书桌")); advanceUntilIdle()
        assertEquals(1, writes)
    }
    @Test fun manualTypingCancelsSpeechAndPreventsDraftOverwrite() = runTest {
        model.startSpeech(); advanceUntilIdle()
        val old = system.id
        model.onInputChanged("护照放在抽屉")
        system.emit(SpeechRecognitionState.Final(old, "钥匙放在玄关柜")); advanceUntilIdle()
        model.submit(); advanceUntilIdle()
        assertEquals("护照", items.value.single().name)
        assertEquals(1, writes)
    }
    @Test fun rapidMicrophoneTapsStartOnlyOneSession() = runTest {
        model.startSpeech(); model.startSpeech(); advanceUntilIdle()
        assertEquals(1, system.startCount)
    }
    @Test fun clearReleasesBothEngines() = runTest {
        model.startSpeech(); advanceUntilIdle()
        store.clear()
        assertEquals(1, system.releaseCount)
        assertEquals(1, sherpa.releaseCount)
    }
    @Test fun unknownFinalDoesNotWrite() = runTest {
        model.startSpeech(); advanceUntilIdle()
        system.emit(SpeechRecognitionState.Final(system.id, "今天天气如何")); advanceUntilIdle()
        assertEquals(0, reads + writes)
        assertEquals("换个说法试试", model.state.value.result?.title)
    }

    /** Intentionally violates adapter guards to test the independent ViewModel session boundary. */
    private class UnreliableInput : SpeechInput {
        override val state = MutableStateFlow<SpeechRecognitionState>(SpeechRecognitionState.Idle)
        override val metrics = MutableStateFlow<AsrMetrics?>(null)
        override val availability = MutableStateFlow(SpeechAvailability(true, "Test", "FAKE"))
        var id = ""
        var startCount = 0
        var cancelCount = 0
        var releaseCount = 0
        fun emit(value: SpeechRecognitionState) { state.value = value }
        override fun startListening(sessionId: String) { id = sessionId; startCount++; emit(SpeechRecognitionState.Listening(id)) }
        override fun stopListening() { emit(SpeechRecognitionState.Finalizing(id)) }
        override fun cancel() { cancelCount++; emit(SpeechRecognitionState.Idle) }
        override fun release() { releaseCount++; cancel() }
    }
}
