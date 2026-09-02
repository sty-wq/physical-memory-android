package dev.local.physicalmemory

import dev.local.physicalmemory.domain.*
import dev.local.physicalmemory.domain.draft.*
import org.junit.Assert.*
import org.junit.Test

class ItemEditDraftTest {
    private val original=ItemState(1,"牛奶","冰箱",0,1,1,listOf(InventoryUnit(2,1,null,1,1)))

    @Test fun countChangesPreserveRetainedDatesAndNeverRemoveExistingStock() {
        val initial=ItemEditDraft(original,addedCountText="2",addedUnits=listOf(DraftUnit(expiryDate="2026-10-01"),DraftUnit()))
        val larger=initial.withAddedCount("3")
        assertEquals(initial.addedUnits,larger.addedUnits.take(2));assertEquals(4,larger.quantity)
        val invalid=larger.withAddedCount("")
        assertEquals(larger.addedUnits,invalid.addedUnits);assertTrue(invalid.errors().isNotEmpty())
        val smaller=invalid.withAddedCount("1")
        assertEquals(initial.addedUnits.take(1),smaller.addedUnits);assertEquals(2,smaller.quantity)
        val zero=smaller.withAddedCount("0")
        assertEquals(original.quantity,zero.quantity);assertEquals(original,zero.original)
        assertTrue(zero.confirmedRemovedUnitIds.isEmpty());assertTrue(zero.errors().isEmpty())
    }

    @Test fun invalidCountsAndDatesCannotBeSaved() {
        for(value in listOf("", "-1", "101", "1.5", "奶")) {
            assertTrue(ItemEditDraft(original).withAddedCount(value).errors().isNotEmpty())
        }
        assertTrue(ItemEditDraft(original).withAddedCount("100").errors().isEmpty())
        assertTrue(ItemEditDraft(original,addedCountText="1",addedUnits=listOf(DraftUnit(expiryDate="2026-02-30"))).errors().isNotEmpty())
        assertTrue(ItemEditDraft(original,addedCountText="1",addedUnits=listOf(DraftUnit(expiryDate="2028-02-29"))).errors().isEmpty())
    }
}
