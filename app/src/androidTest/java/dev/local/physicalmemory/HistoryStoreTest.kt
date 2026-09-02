package dev.local.physicalmemory

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.history.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class HistoryStoreTest {
    @Test fun completedOperationsPersistAndDuplicateKeysDoNotCreateMoreRows():Unit=runBlocking {
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val name="history-test-${UUID.randomUUID()}.db"
        val record=HistoryRecord("draft-1",7,"牛奶","增加 3 袋牛奶",100)
        var db=Room.databaseBuilder(ctx,HistoryDatabase::class.java,name).build()
        try {
            RoomHistoryStore(db.historyDao()).append(record);RoomHistoryStore(db.historyDao()).append(record)
            db.close();db=Room.databaseBuilder(ctx,HistoryDatabase::class.java,name).build()
            assertEquals(listOf(record),RoomHistoryStore(db.historyDao()).observe().first())
            db.openHelper.readableDatabase.query("PRAGMA table_info(history)").use { c ->
                val columns=buildList {while(c.moveToNext()) add(c.getString(1))}
                assertFalse("quantity" in columns);assertFalse("units" in columns);assertFalse("location" in columns)
            }
        } finally { db.close();ctx.deleteDatabase(name) }
    }
}
