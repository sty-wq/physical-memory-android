package dev.local.physicalmemory.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ItemEntity::class, InventoryUnitEntity::class, ConfirmedDraftEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun itemDao(): ItemDao
    abstract fun inventoryDao(): InventoryDao

    companion object {
        const val NAME = "physical-memory.db"
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE items ADD COLUMN lowStockThreshold INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE TABLE IF NOT EXISTS inventory_units (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, itemId INTEGER NOT NULL, expiryDate TEXT, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(itemId) REFERENCES items(id) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_inventory_units_itemId ON inventory_units(itemId)")
                db.execSQL("CREATE TABLE IF NOT EXISTS confirmed_drafts (draftId TEXT NOT NULL PRIMARY KEY, fingerprint TEXT NOT NULL, itemId INTEGER NOT NULL)")
            }
        }
    }
}
