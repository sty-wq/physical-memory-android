package dev.local.physicalmemory

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Real model/microphone lifecycle; every recording is cancelled, preserving user records. */
class Qwen3LifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun backgroundResumeAndActivityRecreationDoNotSubmitCancelledAudio() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("qwenLifecycle") == "true")
        val repository = (compose.activity.application as MemoryApplication).repository
        val before = runBlocking { repository.getItemNames().map { repository.findItemById(it.id) } }
        startAndWait()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        startAndWait()
        compose.onNodeWithTag("hold-to-talk").performTouchInput { cancel() }
        compose.activityRule.scenario.recreate()
        startAndWait()
        compose.onNodeWithTag("hold-to-talk").performTouchInput { cancel() }
        assertEquals(before, runBlocking { repository.getItemNames().map { repository.findItemById(it.id) } })
    }
    private fun startAndWait() {
        compose.waitUntil(30000) { compose.onAllNodesWithText("按住说话").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("hold-to-talk").performScrollTo().performTouchInput { down(center) }
        compose.waitUntil(30_000) {
            compose.onAllNodesWithText("正在听…", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
