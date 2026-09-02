package dev.local.physicalmemory

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Ambiguity fixture uses only in-memory Room; no writes to the user's database. */
@RunWith(AndroidJUnit4::class)
class FuzzyChoiceUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun ambiguousNamesCanBeSelectedAndReturnLatestLocation() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        val repository = RoomItemRepository(database.itemDao())
        val store = ViewModelStore()
        try {
            val chosen = runBlocking {
                repository.upsertItem("车钥匙", "玄关").also { repository.upsertItem("家钥匙", "包里") }
            }
            lateinit var model: HomeViewModel
            compose.runOnUiThread { model = HomeViewModel(PhysicalMemory(repository)); store.put("fixture", model) }
            compose.setContent {
                val state by model.state.collectAsState()
                MemoryTheme { HomeScreen(state, model::onInputChanged, model::submit, model::reloadRecords, model::selectCandidate) }
            }
            compose.onNodeWithTag("command-input").performTextReplacement("钥匙在哪")
            compose.onNodeWithTag("submit-button").performClick()
            compose.waitUntil(10_000) { model.state.value.result?.suggestions?.size == 2 }
            // Confirmation must fetch the latest row, not the location from the original search.
            runBlocking { repository.upsertItem("车钥匙", "书桌") }
            val before = runBlocking { repository.getItemNames().map { repository.findItemById(it.id) } }
            compose.onNodeWithTag("candidate-${chosen.id}").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("车钥匙在书桌", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("result-text", useUnmergedTree = true).assertTextEquals("车钥匙在书桌")
            assertEquals(before, runBlocking { repository.getItemNames().map { repository.findItemById(it.id) } })
        } finally {
            compose.runOnUiThread { store.clear() }
            database.close()
        }
    }
}
