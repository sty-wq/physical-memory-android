package dev.local.physicalmemory

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.draft.*
import dev.local.physicalmemory.nlu.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StoredItemsRepositoryTest {
    private fun runDb(block:suspend (RoomInventoryRepository,DraftFactory)->Unit)=runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,AppDatabase::class.java).build()
        try {val r=RoomInventoryRepository(db);block(r,DraftFactory(r))} finally {db.close()}
    }
    @Test fun allItemsIncludesBeyondTwentyAndEditsKeepIdentity()=runDb {r,f->
        val milk=r.confirm(f.create(NluResult.ProposeAddUnits("牛奶",2,"袋","冰箱",null),"fixture")).item
        repeat(24) {r.confirm(f.create(NluResult.UpsertItemInfo("物品$it",LocationChange(LocationOp.KEEP,null)),"fixture"))}
        val all=r.observeAll().first();assertEquals(25,all.size);assertEquals(milk.id,all.last().id)
        assertEquals(20,r.recent().first().size)
        val edit=ItemEditDraft(milk,name="早餐牛奶",location="厨房",expiryDates=mapOf(milk.units[0].id to "2026-10-05",milk.units[1].id to ""))
        val saved=r.updateItem(edit)
        assertEquals(milk.id,saved.id);assertEquals(milk.createdAt,saved.createdAt)
        assertEquals(milk.units.map {it.id},saved.units.map {it.id});assertEquals(milk.units.map {it.createdAt},saved.units.map {it.createdAt})
        assertEquals(milk.units[1],saved.units[1]);assertEquals("2026-10-05",saved.units[0].expiryDate)
        assertNull(r.findByName("牛奶"));assertEquals(saved,r.findByName("早餐牛奶"))
        assertEquals(saved,r.observeAll().first().first());assertEquals(25,r.observeAll().first().size)
        assertEquals(saved,r.updateItem(ItemEditDraft(saved))) // No-op does not bump timestamps.
    }
    @Test fun collisionInvalidDateAndRemovedUnitCannotPartiallyWrite()=runDb {r,f->
        val milk=r.confirm(f.create(NluResult.ProposeAddUnits("牛奶",2,"袋","冰箱",null),"fixture")).item
        val other=r.confirm(f.create(NluResult.UpsertItemInfo("果汁",LocationChange(LocationOp.SET,"桌子")),"fixture")).item
        val candidates=listOf(ItemEditDraft(milk,name="果汁",location="错误位置"),
            ItemEditDraft(milk,expiryDates=milk.units.associate {it.id to "2026-02-30"}),
            ItemEditDraft(milk,expiryDates=emptyMap()),
            ItemEditDraft(milk,confirmedRemovedUnitIds=setOf(Long.MAX_VALUE)),
            ItemEditDraft(milk,addedCountText="2",addedUnits=listOf(DraftUnit())),
            ItemEditDraft(milk,addedCountText="1",addedUnits=listOf(DraftUnit(expiryDate="2026-02-30"))))
        for(d in candidates) {
            assertTrue(runCatching {r.updateItem(d)}.isFailure)
            assertEquals(milk,r.findById(milk.id));assertEquals(other,r.findById(other.id))
        }
    }
    @Test fun staleEditCannotRestoreDeletedStockAndZeroStockStaysListed()=runDb {r,f->
        val milk=r.confirm(f.create(NluResult.ProposeAddUnits("牛奶",2,"袋","冰箱",null),"fixture")).item
        val stale=ItemEditDraft(milk,name="过期编辑",location="桌子")
        val one=r.deleteInventoryUnit(milk.id,milk.units[0])
        assertTrue(runCatching {r.updateItem(stale)}.isFailure);assertEquals(one,r.findById(milk.id))
        r.deleteInventoryUnit(one.id,one.units.single())
        val zero=r.observeAll().first().single();assertEquals(milk.id,zero.id);assertEquals(0,zero.quantity)
        assertEquals(milk.createdAt,zero.createdAt)
    }
    @Test fun stockAndDatesSaveAtomicallyWithReplayAndStaleProtection()=runDb {r,f->
        val original=r.confirm(f.create(NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱",null),"fixture")).item
        val edit=ItemEditDraft(original,name="早餐牛奶",location="冷藏室",
            expiryDates=original.units.associate {it.id to if(it==original.units[1]) "2026-10-18" else ""},
            addedCountText="2",addedUnits=listOf(DraftUnit(expiryDate="2026-10-20"),DraftUnit()),
            confirmedRemovedUnitIds=setOf(original.units.first().id))
        val changed=r.updateItem(edit)
        assertEquals(4,changed.quantity);assertEquals(original.id,changed.id);assertEquals(original.createdAt,changed.createdAt)
        assertEquals("早餐牛奶",changed.name);assertEquals("冷藏室",changed.location)
        assertEquals(original.units[1].id,changed.units[0].id);assertEquals(original.units[1].createdAt,changed.units[0].createdAt)
        assertEquals("2026-10-18",changed.units[0].expiryDate);assertEquals(original.units[2],changed.units[1])
        assertEquals("2026-10-20",changed.units[2].expiryDate);assertNull(changed.units[3].expiryDate)
        assertTrue(changed.units.all {it.id!=original.units.first().id && it.itemId==original.id})
        assertTrue(runCatching {r.updateItem(edit)}.isFailure) // Cannot add the same saved draft twice.
        assertEquals(changed,r.findById(original.id))
        val stale=ItemEditDraft(changed).withAddedCount("2")
        val zero=r.updateItem(ItemEditDraft(changed,confirmedRemovedUnitIds=changed.units.map {it.id}.toSet()))
        assertEquals(0,zero.quantity);assertEquals(original.id,zero.id)
        assertEquals(listOf(zero),r.observeAll().first())
        assertTrue(runCatching {r.updateItem(stale)}.isFailure);assertEquals(zero,r.findById(zero.id))
        val refilled=r.updateItem(ItemEditDraft(zero,addedCountText="1",addedUnits=listOf(DraftUnit(expiryDate="2026-12-31"))))
        assertEquals(1,refilled.quantity);assertEquals(zero.id,refilled.id);assertEquals("2026-12-31",refilled.units.single().expiryDate)
        val cleared=r.updateItem(ItemEditDraft(refilled,expiryDates=mapOf(refilled.units.single().id to "")))
        assertNull(cleared.units.single().expiryDate);assertEquals(refilled.units.single().id,cleared.units.single().id)
    }
}
