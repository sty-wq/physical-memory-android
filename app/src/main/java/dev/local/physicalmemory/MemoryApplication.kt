package dev.local.physicalmemory

import android.app.Application
import androidx.room.Room
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomItemRepository
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.ItemRepository
import dev.local.physicalmemory.voice.AsrLog
import dev.local.physicalmemory.history.HistoryDatabase
import dev.local.physicalmemory.history.RoomHistoryStore

class MemoryApplication : Application() {
    val asrLog by lazy { AsrLog(this) }
    private val database by lazy {
        Room.databaseBuilder(this, AppDatabase::class.java, AppDatabase.NAME).addMigrations(AppDatabase.MIGRATION_1_2).build()
    }
    val repository: ItemRepository by lazy { RoomItemRepository(database.itemDao()) }
    val inventoryRepository by lazy { RoomInventoryRepository(database) }
    private val historyDatabase by lazy { Room.databaseBuilder(this,HistoryDatabase::class.java,"operation-history.db").build() }
    val historyStore by lazy { RoomHistoryStore(historyDatabase.historyDao()) }
}
