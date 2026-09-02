package dev.local.physicalmemory

import android.Manifest
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run on the dedicated emulator with RECORD_AUDIO denied; never records sound or writes item data. */
@RunWith(AndroidJUnit4::class)
class SpeechPermissionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun permissionDenialLeavesTextUsableAndActivityRecreationPreservesDraft() {
        val app = compose.activity.application as MemoryApplication
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO))
        val before = runBlocking { app.repository.getItemNames().map { app.repository.findItemById(it.id) } }
        compose.waitUntil(30000) { compose.onAllNodesWithText("按住说话").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("hold-to-talk").performScrollTo().performTouchInput { down(center);up() }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        compose.waitUntil(5_000) {
            automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(
                "com.android.permissioncontroller:id/permission_deny_button")?.isNotEmpty() == true
        }
        val deny = automation.rootInActiveWindow.findAccessibilityNodeInfosByViewId(
            "com.android.permissioncontroller:id/permission_deny_button").first()
        assertTrue(deny.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("请允许麦克风权限", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("command-input").performScrollTo().performTextReplacement("连花清瘟在哪")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("command-input").performScrollTo().assertTextContains("连花清瘟在哪")
        compose.onNodeWithTag("parse-button").performScrollTo().assertIsEnabled()
        assertEquals(before, runBlocking { app.repository.getItemNames().map { app.repository.findItemById(it.id) } })
    }
}
