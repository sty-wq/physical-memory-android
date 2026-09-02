package dev.local.physicalmemory

import dev.local.physicalmemory.domain.matching.FuzzyItemMatcher
import dev.local.physicalmemory.domain.matching.NameMatch
import dev.local.physicalmemory.domain.model.ItemName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FuzzyItemMatcherTest {
    private fun match(query: String, vararg names: String): NameMatch = runBlocking {
        FuzzyItemMatcher().match(query, names.mapIndexed { i, name -> ItemName(i + 1L, name) })
    }

    @Test fun reportedTypoResolvesToStoredName() {
        assertEquals(NameMatch.Resolved(ItemName(1, "连花清瘟")), match("莲花清瘟", "连花清瘟", "钥匙"))
    }
    @Test fun adjacentTranspositionResolves() {
        assertEquals(NameMatch.Resolved(ItemName(1, "连花清瘟")), match("连花瘟清", "连花清瘟"))
    }
    @Test fun omittedCharacterAsksForConfirmation() {
        assertEquals(NameMatch.NeedsConfirmation(listOf(ItemName(1, "连花清瘟"))), match("连花瘟", "连花清瘟"))
    }
    @Test fun extraCharacterAsksForConfirmation() {
        assertTrue(match("连花小清瘟", "连花清瘟") is NameMatch.NeedsConfirmation)
    }
    @Test fun tiedTyposNeverChooseArbitrarily() {
        val result = match("莲花清瘟", "连花清瘟", "莲花清温") as NameMatch.NeedsConfirmation
        assertEquals(setOf("连花清瘟", "莲花清温"), result.candidates.map { it.name }.toSet())
    }
    @Test fun closeRunnerUpRequiresConfirmation() {
        assertTrue(match("莲花清瘟", "连花清瘟", "莲花清瘟胶囊") is NameMatch.NeedsConfirmation)
    }
    @Test fun partialNameAlwaysNeedsConfirmation() {
        assertEquals(NameMatch.NeedsConfirmation(listOf(ItemName(1, "车钥匙"))), match("钥匙", "车钥匙"))
    }
    @Test fun shortTypoNeedsConfirmation() {
        assertTrue(match("钥是", "钥匙") is NameMatch.NeedsConfirmation)
    }
    @Test fun singleCharacterNeverFuzzyMatches() {
        assertEquals(NameMatch.None, match("钥", "钥匙", "药"))
    }
    @Test fun unrelatedOrEmptyQueryDoesNotMatch() {
        assertEquals(NameMatch.None, match("护照", "连花清瘟", "钥匙"))
        assertEquals(NameMatch.None, match(" ", "钥匙"))
        assertEquals(NameMatch.None, match("钥匙"))
    }
    @Test fun caseWidthAndSpacingNormalizeWithoutChangingStoredName() {
        assertEquals(NameMatch.Resolved(ItemName(1, "SD 卡")), match("ｓｄ卡", "SD 卡"))
    }
    @Test fun normalizedCollisionNeedsConfirmation() {
        assertTrue(match("sd卡", "SD卡", "SD 卡") is NameMatch.NeedsConfirmation)
    }
    @Test fun twoEditsInLongNamesNeedConfirmation() {
        assertTrue(match("无线蓝亚尔机", "无线蓝牙耳机") is NameMatch.NeedsConfirmation)
        assertEquals(NameMatch.None, match("莲花青瘟", "连花清瘟"))
    }
    @Test fun candidatesAreCappedAndStable() {
        val names = arrayOf("车钥匙", "家钥匙", "柜钥匙", "房钥匙", "门钥匙", "仓钥匙")
        val result = match("钥匙", *names) as NameMatch.NeedsConfirmation
        assertEquals(5, result.candidates.size)
        assertEquals(names.sorted().take(5), result.candidates.map { it.name })
    }
    @Test fun unicodeCodePointsStayIntact() {
        assertEquals(NameMatch.Resolved(ItemName(1, "🔑钥匙挂件")), match("🔑钥匙挂链", "🔑钥匙挂件"))
    }
}
