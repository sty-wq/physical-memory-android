package dev.local.physicalmemory.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import dev.local.physicalmemory.domain.model.ItemName

@Dao
abstract class ItemDao {
    @Query("SELECT * FROM items WHERE name = :name LIMIT 1")
    abstract suspend fun findItem(name: String): ItemEntity?

    @Query("SELECT id, name FROM items ORDER BY id")
    abstract suspend fun getItemNames(): List<ItemName>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    abstract suspend fun findItemById(id: Long): ItemEntity?

    @Query("SELECT * FROM items ORDER BY updatedAt DESC, id DESC LIMIT 20")
    abstract fun getRecentItems(): Flow<List<ItemEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertItem(item: ItemEntity): Long

    @Query("""
        UPDATE items SET location = :location,
        updatedAt = CASE WHEN :now > updatedAt THEN :now ELSE updatedAt + 1 END
        WHERE name = :name
    """)
    protected abstract suspend fun updateLocation(name: String, location: String, now: Long)

    /** Unique name + transaction protects simultaneous saves without replacing row identity. */
    @Transaction
    open suspend fun upsertItem(name: String, location: String, now: Long): ItemEntity {
        val id = insertItem(ItemEntity(name = name, location = location, createdAt = now, updatedAt = now))
        if (id == -1L) updateLocation(name, location, now)
        return checkNotNull(findItem(name))
    }
}
