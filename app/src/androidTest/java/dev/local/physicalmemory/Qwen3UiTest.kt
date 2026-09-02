package dev.local.physicalmemory

import android.os.Looper
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomItemRepository
import dev.local.physicalmemory.domain.PhysicalMemory
import dev.local.physicalmemory.ui.home.*
import dev.local.physicalmemory.ui.theme.MemoryTheme
import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class Qwen3UiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun recognizingKeepsUiResponsiveAndCancelledDecodeCannotWrite() {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val repository = RoomItemRepository(db.itemDao())
        val store = ViewModelStore()
        val releaseDecode = CountDownLatch(1)
        val decoderClosed = AtomicInteger()
        lateinit var input: Qwen3AsrSpeechInput
        lateinit var model: HomeViewModel
        try {
            input = Qwen3AsrSpeechInput(
                decoderFactory = { object : OfflineAsrDecoder {
                    override fun decode(samples: FloatArray, sampleRate: Int): String {
                        check(Looper.myLooper() != Looper.getMainLooper())
                        check(releaseDecode.await(10, TimeUnit.SECONDS))
                        return "钥匙放在不该写入的位置"
                    }
                    override fun close() { decoderClosed.incrementAndGet() }
                } },
                recorderFactory = { object : PcmRecorder {
                    override fun start() {}
                    override fun read(buffer: ShortArray): Int { buffer.fill(1000); input.stopListening(); return buffer.size }
                    override fun stop() {}
                    override fun close() {}
                } },
            )
            compose.runOnUiThread {
                model = HomeViewModel(PhysicalMemory(repository), speechInputs = mapOf(SpeechEngine.QWEN3_ASR to input))
                store.put("home", model)
            }
            compose.setContent {
                val state by model.state.collectAsState()
                MemoryTheme { HomeScreen(state, model::onInputChanged, model::submit, model::reloadRecords, model::selectCandidate,
                    model::selectEngine, model::startSpeech, model::cancelSpeech) }
            }
            compose.onNodeWithTag("microphone-button").performScrollTo().performClick()
            compose.waitUntil(5_000) { model.state.value.speechState is SpeechRecognitionState.Recognizing }
            compose.onNodeWithTag("speech-status").performScrollTo().assertTextEquals("正在识别…")
            compose.onNodeWithTag("cancel-speech").performClick()
            compose.onNodeWithTag("command-input").performScrollTo().performTextReplacement("护照放在抽屉")
            compose.onNodeWithTag("submit-button").performScrollTo().performClick()
            compose.waitUntil(5_000) { model.state.value.result?.title == "已记住" }
            assertEquals("抽屉", runBlocking { repository.findItem("护照") }?.location)
            releaseDecode.countDown()
            compose.runOnUiThread { store.clear() }
            compose.waitUntil(5_000) { decoderClosed.get() == 1 }
            assertNull(runBlocking { repository.findItem("钥匙") })
        } finally {
            releaseDecode.countDown()
            compose.runOnUiThread { store.clear() }
            db.close()
        }
    }
}
