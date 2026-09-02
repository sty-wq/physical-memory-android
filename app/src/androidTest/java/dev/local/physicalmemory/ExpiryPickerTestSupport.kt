package dev.local.physicalmemory

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal fun calendarDay(day: Int): SemanticsMatcher {
    val locale=InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.locales[0]
    val label=LocalDate.now().withDayOfMonth(day).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
    return hasText(label,substring=true) and hasClickAction() and hasAnyAncestor(hasTestTag("expiry-calendar"))
}

/** Select a visible day in the initial month using the same calendar controls as the user. */
internal fun selectExpiryDay(compose: ComposeTestRule,index: Int,day: Int) {
    compose.onNodeWithTag("draft-screen").performScrollToNode(hasTestTag("draft-expiry-$index"))
    compose.onNodeWithTag("draft-expiry-$index").performClick()
    compose.onNode(calendarDay(day))
        .performScrollTo().performClick()
    compose.onNodeWithTag("confirm-expiry-date").performClick()
}
