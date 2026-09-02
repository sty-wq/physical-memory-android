package dev.local.physicalmemory

import android.content.Context
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.draft.*
import dev.local.physicalmemory.nlu.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class InventoryRepositoryTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun up(location: String)=NluResult.UpsertItemInfo("牛奶",LocationChange(LocationOp.SET,location))
    private fun add(count: Int, location: String? = null)=NluResult.ProposeAddUnits("牛奶",count,"袋",location,null)

    @Test fun locationChangeKeepsEveryUnitAndNoOpKeepsTimestamp(): Unit = runBlocking {
        Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build().use { db ->
            var time=100L; val repo=RoomInventoryRepository(db) { time++ }; val factory=DraftFactory(repo)
            repo.confirm(factory.create(add(3,"冰箱"),"fixture"))
            val before=repo.findByName("牛奶")!!
            val draft=factory.create(up("桌子上"),"牛奶在桌子上")
            assertTrue(draft is UpdateItemDraft); assertEquals("冰箱",draft.data.current!!.location)
            assertEquals("桌子上",draft.data.proposedLocation)
            assertEquals(before,repo.findByName("牛奶")) // read-only proposal
            val result=repo.confirm(draft)
            assertEquals("桌子上",result.item.location); assertEquals(before.units,result.item.units)
            assertEquals(3,result.item.quantity); assertEquals(before.id,result.item.id)
            val same=factory.create(up("桌子上"),"same")
            val unchanged=repo.confirm(same)
            assertTrue(unchanged.noOp); assertEquals(result.item,unchanged.item)
        }
    }
    @Test fun addExistingReusesOrChangesWholeItemLocationAndReplayIsIdempotent(): Unit = runBlocking {
        Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build().use { db ->
            val repo=RoomInventoryRepository(db); val f=DraftFactory(repo)
            repo.confirm(f.create(add(2,"冰箱"),"fixture"))
            val old=repo.findByName("牛奶")!!
            val draft=f.create(add(3),"增加三袋牛奶")
            assertEquals("冰箱",draft.data.proposedLocation); assertEquals(3,draft.data.units.size)
            val first=repo.confirm(draft); assertEquals(5,first.item.quantity); assertEquals("冰箱",first.item.location)
            assertTrue(repo.confirm(draft).replay); assertEquals(5,repo.findByName("牛奶")!!.quantity)
            val changed=repo.confirm(f.create(add(3,"桌子上"),"add3desk"))
            assertEquals(8,changed.item.quantity); assertEquals("桌子上",changed.item.location)
            assertEquals(old.units,changed.item.units.take(2))
        }
    }
    @Test fun twoPlusThreeWithLocationConflictIsFiveAndUnitsNeverOwnLocation(): Unit = runBlocking {
        Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build().use { db ->
            val repo=RoomInventoryRepository(db); val f=DraftFactory(repo)
            repo.confirm(f.create(add(2,"冰箱"),"fixture"))
            val old=repo.findByName("牛奶")!!
            val d=f.create(add(3,"桌子上"),"increase")
            assertEquals("冰箱",d.data.current!!.location)
            val result=repo.confirm(d).item
            assertEquals(5,result.quantity); assertEquals("桌子上",result.location); assertEquals(old.units,result.units.take(2))
            db.openHelper.readableDatabase.query("PRAGMA table_info(inventory_units)").use { c ->
                val columns=buildList { while(c.moveToNext()) add(c.getString(1)) }
                assertEquals(listOf("id","itemId","expiryDate","createdAt","updatedAt"),columns)
            }
        }
    }
    @Test fun editableExpirySingleDeletionAndZeroStockPreserveItem(): Unit = runBlocking {
        Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build().use { db ->
            val repo=RoomInventoryRepository(db); val f=DraftFactory(repo)
            val d=f.create(NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱",DefaultExpiry("2026-09-03","明天")),"input")
            assertTrue(d.data.units.all { it.expiryDate=="2026-09-03" })
            val edited=d.withData(d.data.copy(units=d.data.units.mapIndexed { i,u -> u.copy(expiryDate=when(i) {0->"2026-09-04";1->"";else->u.expiryDate}) }))
            val first=repo.confirm(edited).item
            assertEquals(listOf("2026-09-04",null,"2026-09-03"),first.units.map { it.expiryDate })
            val oneLess=repo.deleteInventoryUnit(first.id,first.units[1])
            assertEquals(listOf(first.units[0],first.units[2]),oneLess.units)
            oneLess.units.forEach { repo.deleteInventoryUnit(first.id,it) }
            val empty=repo.findById(first.id)!!
            assertEquals(0,empty.quantity); assertEquals("冰箱",empty.location)
            assertTrue(runCatching { repo.deleteInventoryUnit(first.id,first.units[1]) }.isFailure)
        }
    }
    @Test fun staleDraftAndInvalidDateCannotWrite(): Unit = runBlocking {
        Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build().use { db ->
            val repo=RoomInventoryRepository(db); val f=DraftFactory(repo)
            repo.confirm(f.create(add(2,"冰箱"),"fixture"))
            val stale=f.create(up("桌子上"),"stale")
            repo.confirm(f.create(add(1),"newer"))
            val current=repo.findByName("牛奶")
            assertTrue(runCatching { repo.confirm(stale) }.isFailure); assertEquals(current,repo.findByName("牛奶"))
            val draft=f.create(add(1),"date")
            val invalid=draft.withData(draft.data.copy(units=listOf(DraftUnit(expiryDate="2026-02-30"))))
            assertTrue(runCatching { repo.confirm(invalid) }.isFailure); assertEquals(current,repo.findByName("牛奶"))
        }
    }
    @Test fun keepAndEditedNameResolveExactIdentity(): Unit = runBlocking {
        Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build().use { db ->
            val repo=RoomInventoryRepository(db); val f=DraftFactory(repo)
            val keep=NluResult.UpsertItemInfo("R8",LocationChange(LocationOp.KEEP,null))
            repo.confirm(f.create(keep,"记住R8")); assertEquals("",repo.findByName("R8")!!.location)
            val d=f.create(NluResult.UpsertItemInfo("AD两百",LocationChange(LocationOp.SET,"器材柜")),"raw")
            val edited=f.changeName(d,"AD200"); repo.confirm(edited)
            assertNull(repo.findByName("AD两百")); assertEquals("器材柜",repo.findByName("AD200")!!.location)
            repo.confirm(f.create(up("冰箱"),"milk"))
            repo.confirm(f.create(NluResult.UpsertItemInfo("牛奶",LocationChange(LocationOp.KEEP,null)),"keep"))
            assertEquals("冰箱",repo.findByName("牛奶")!!.location)
        }
    }
    @Test fun migrationPreservesV1RecordsWithoutInventingUnits(): Unit = runBlocking {
        val name="v2-migration-${UUID.randomUUID()}.db"
        try {
            context.openOrCreateDatabase(name,Context.MODE_PRIVATE,null).use { sqlite ->
                sqlite.execSQL("CREATE TABLE items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, location TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)")
                sqlite.execSQL("CREATE UNIQUE INDEX index_items_name ON items(name)")
                sqlite.execSQL("INSERT INTO items VALUES (7,'连花清瘟','药柜',11,22)"); sqlite.version=1
            }
            Room.databaseBuilder(context,AppDatabase::class.java,name).addMigrations(AppDatabase.MIGRATION_1_2).build().use { db ->
                val item=RoomInventoryRepository(db).findByName("连花清瘟")!!
                assertEquals(7L,item.id); assertEquals("药柜",item.location); assertEquals(11L,item.createdAt)
                assertEquals(22L,item.updatedAt); assertEquals(0,item.quantity); assertEquals(0,item.lowStockThreshold)
            }
        } finally { context.deleteDatabase(name) }
    }
}

private inline fun <T> AppDatabase.use(block: (AppDatabase) -> T): T = try { block(this) } finally { close() }
