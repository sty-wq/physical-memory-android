package dev.local.physicalmemory

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
import dev.local.physicalmemory.ui.home.HomeScreen
import dev.local.physicalmemory.ui.home.HomeViewModel
import dev.local.physicalmemory.ui.theme.MemoryTheme
import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun fakePartialFinalSwitchAndTextUseTheSameBusinessPath() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val repository = RoomItemRepository(database.itemDao())
        val system = FakeSpeechInput()
        val sherpa = FakeSpeechInput(SpeechEngine.SHERPA)
        val store = ViewModelStore()
        try {
            lateinit var model: HomeViewModel
            compose.runOnUiThread {
                model = HomeViewModel(PhysicalMemory(repository), speechInputs = mapOf(SpeechEngine.SYSTEM to system, SpeechEngine.SHERPA to sherpa))
                store.put("home", model)
            }
            compose.setContent {
                val state by model.state.collectAsState()
                MemoryTheme {
                    HomeScreen(state, model::onInputChanged, model::submit, model::reloadRecords, model::selectCandidate,
                        model::selectEngine, model::startSpeech, model::cancelSpeech)
                }
            }
            compose.onNodeWithTag("microphone-button").performScrollTo().performClick()
            compose.runOnIdle { system.emitPartial("钥匙放在玄关柜") }
            compose.onNodeWithTag("speech-status").performScrollTo().assertTextEquals("钥匙放在玄关柜")
            assertTrue(runBlocking { repository.getItemNames().isEmpty() })
            compose.runOnIdle { system.emitFinal("钥匙放在玄关柜") }
            compose.waitUntil(5_000) { model.state.value.result?.title == "已记住" }
            val stored = runBlocking { repository.findItem("钥匙") }
            assertEquals("玄关柜", stored?.location)
            compose.onNodeWithTag("engine-SHERPA").performScrollTo().performClick()
            compose.onNodeWithTag("microphone-button").performScrollTo().performClick()
            compose.runOnIdle { sherpa.emitPartial("钥匙在"); sherpa.emitFinal("钥匙在哪") }
            compose.waitUntil(5_000) { model.state.value.result?.text == "钥匙在玄关柜" }
            assertEquals(stored, runBlocking { repository.findItem("钥匙") })
            compose.onNodeWithTag("result-text", useUnmergedTree = true).performScrollTo().assertTextEquals("钥匙在玄关柜")
            compose.onNodeWithTag("microphone-button").performScrollTo().performClick()
            compose.runOnIdle { sherpa.emitError() }
            compose.onNodeWithTag("command-input").performScrollTo().performTextReplacement("护照放在抽屉")
            compose.onNodeWithTag("submit-button").performScrollTo().performClick()
            compose.waitUntil(5_000) { model.state.value.result?.text == "已记住：护照在抽屉" }
            assertEquals(2, runBlocking { repository.getItemNames().size })
        } finally {
            compose.runOnUiThread { store.clear() }
            database.close()
        }
        assertEquals(1, system.releaseCount)
        assertEquals(1, sherpa.releaseCount)
    }
}
