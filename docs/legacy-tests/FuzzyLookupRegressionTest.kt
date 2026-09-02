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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Read-only regression against the user's existing 连花清瘟 record. Run directly with adb. */
@RunWith(AndroidJUnit4::class)
class FuzzyLookupRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun submit(text: String, expected: String) {
        compose.onNodeWithTag("command-input").performScrollTo().performTextReplacement(text)
        compose.onNodeWithTag("submit-button").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(expected, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("result-text", useUnmergedTree = true).assertTextEquals(expected)
    }

    @Test fun typoFindsExistingMedicineAndChangesNoRecords() {
        val repository = (compose.activity.application as MemoryApplication).repository
        val before = runBlocking { repository.getItemNames().map { repository.findItemById(it.id) } }
        val medicine = before.filterNotNull().single { it.name == "连花清瘟" }
        submit("连花清瘟在哪", "连花清瘟在${medicine.location}")
        submit("莲花清瘟在哪", "按相近名称匹配到“连花清瘟”\n连花清瘟在${medicine.location}")
        assertEquals(before, runBlocking { repository.getItemNames().map { repository.findItemById(it.id) } })
        compose.onNodeWithTag("command-input").performScrollTo()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(instrumentation.targetContext.getExternalFilesDir(null), "fuzzy-lookup-fixed.png")
            .outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
