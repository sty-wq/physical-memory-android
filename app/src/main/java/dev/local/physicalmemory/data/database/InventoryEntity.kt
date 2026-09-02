package dev.local.physicalmemory.data.database

import androidx.room.*

@Entity(tableName = "inventory_units", foreignKeys = [ForeignKey(entity = ItemEntity::class,
    parentColumns = ["id"], childColumns = ["itemId"], onDelete = ForeignKey.RESTRICT)], indices = [Index("itemId")])
data class InventoryUnitEntity(@PrimaryKey(autoGenerate = true) val id: Long = 0, val itemId: Long,
    val expiryDate: String?, val createdAt: Long, val updatedAt: Long)

/** Transaction receipt prevents the same confirmed draft being applied twice, even after process recreation. */
@Entity(tableName = "confirmed_drafts")
data class ConfirmedDraftEntity(@PrimaryKey val draftId: String, val fingerprint: String, val itemId: Long)

data class ItemWithUnits(@Embedded val item: ItemEntity,
    @Relation(parentColumn = "id", entityColumn = "itemId") val units: List<InventoryUnitEntity>)

@Dao
interface InventoryDao {
    @Transaction @Query("SELECT * FROM items WHERE name = :name LIMIT 1")
    suspend fun byName(name: String): ItemWithUnits?
    @Transaction @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun byId(id: Long): ItemWithUnits?
    @Transaction @Query("SELECT * FROM items ORDER BY updatedAt DESC, id DESC LIMIT 20")
    fun recent(): kotlinx.coroutines.flow.Flow<List<ItemWithUnits>>
    @Transaction @Query("SELECT * FROM items ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): kotlinx.coroutines.flow.Flow<List<ItemWithUnits>>
    @Query("UPDATE items SET name = :name, location = :location, updatedAt = :now WHERE id = :id")
    suspend fun updateInfo(id: Long, name: String, location: String, now: Long): Int
    @Query("UPDATE inventory_units SET expiryDate = :expiry, updatedAt = :now WHERE id = :id AND itemId = :itemId")
    suspend fun updateExpiry(id: Long, itemId: Long, expiry: String?, now: Long): Int
    @Insert suspend fun insertItem(item: ItemEntity): Long
    @Insert suspend fun insertUnits(units: List<InventoryUnitEntity>)
    @Query("UPDATE items SET location = :location, updatedAt = :now WHERE id = :id AND location != :location")
    suspend fun changeLocation(id: Long, location: String, now: Long): Int
    @Query("SELECT * FROM confirmed_drafts WHERE draftId = :draftId")
    suspend fun receipt(draftId: String): ConfirmedDraftEntity?
    @Insert suspend fun insertReceipt(receipt: ConfirmedDraftEntity)
    @Query("SELECT * FROM inventory_units WHERE id = :id AND itemId = :itemId")
    suspend fun unit(id: Long, itemId: Long): InventoryUnitEntity?
    @Query("DELETE FROM inventory_units WHERE id = :id AND itemId = :itemId")
    suspend fun deleteUnit(id: Long, itemId: Long): Int
}
