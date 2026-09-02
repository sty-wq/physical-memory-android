package dev.local.physicalmemory

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Acceptance fixture for the dedicated emulator: writes only the test item's current location. */
@RunWith(AndroidJUnit4::class)
class HomeAcceptanceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun submit(text: String, expected: String, useIme: Boolean = false) {
        compose.onNodeWithTag("command-input").performScrollTo().performTextReplacement(text)
        if (useIme) compose.onNodeWithTag("command-input").performImeAction()
        else compose.onNodeWithTag("submit-button").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(expected, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("result-text", useUnmergedTree = true).assertTextEquals(expected)
    }

    @Test fun storeFindMissingUpdateAndImeWorkThroughActualComposeUi() {
        compose.onNodeWithTag("submit-button").assertIsNotEnabled()
        submit("钥匙放在玄关柜", "已记住：钥匙在玄关柜")
        compose.onNodeWithTag("command-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
        compose.onNodeWithTag("location-钥匙").performScrollTo().assertTextEquals("玄关柜")

        submit("钥匙在哪", "钥匙在玄关柜")
        submit("护照在哪", "还没有记录“护照”的位置")
        submit("钥匙放在书桌上", "已记住：钥匙在书桌上")
        submit("钥匙在哪", "钥匙在书桌上", useIme = true)
        compose.onNodeWithTag("location-钥匙").performScrollTo().assertTextEquals("书桌上")

        // Recreating the Activity must restore the same database-backed recent record.
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("书桌上").fetchSemanticsNodes().isNotEmpty()
        }
        submit("钥匙在哪", "钥匙在书桌上")
        compose.onNodeWithTag("command-input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("submit-button").assertIsDisplayed()
        compose.onNodeWithText("最近记录").assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        val file = File(instrumentation.targetContext.getExternalFilesDir(null), "physical-memory-v0.png")
        file.outputStream().use { stream -> assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, stream)) }
        screenshot.recycle()
    }
}
