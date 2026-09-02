package dev.local.physicalmemory

import android.graphics.Bitmap
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.io.File

/** Replays only the user's reported command; never clears, deletes, or uninstalls app data. */
@RunWith(AndroidJUnit4::class)
class LocationStatementRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun submit(text: String, expected: String) {
        compose.onNodeWithTag("command-input").performScrollTo().performTextReplacement(text)
        compose.onNodeWithTag("submit-button").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(expected, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("result-text", useUnmergedTree = true).assertTextEquals(expected)
    }

    @Test fun locationStatementWithAttributiveVerbStoresAndFindsSameItem() {
        val repository = (compose.activity.application as MemoryApplication).repository
        val before = runBlocking { repository.findItem("连花清瘟") }
        submit("连花清瘟在放药的柜子里", "已记住：连花清瘟在放药的柜子里")
        val stored = runBlocking { repository.findItem("连花清瘟") }
        assertNotNull(stored)
        assertEquals("放药的柜子里", stored!!.location)
        if (before != null) {
            assertEquals(before.id, stored.id)
            assertEquals(before.createdAt, stored.createdAt)
        }
        submit("连花清瘟在哪", "连花清瘟在放药的柜子里")
        assertEquals(stored, runBlocking { repository.findItem("连花清瘟") })
        compose.onNodeWithTag("location-连花清瘟").performScrollTo().assertTextEquals("放药的柜子里")
        compose.onNodeWithTag("command-input").performScrollTo()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "location-statement-fixed.png")
            .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
