package dev.local.physicalmemory.data.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo
import dev.local.physicalmemory.domain.model.ItemRecord

@Entity(tableName = "items", indices = [Index(value = ["name"], unique = true)])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val location: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "0") val lowStockThreshold: Int = 0,
) {
    fun toRecord() = ItemRecord(id, name, location, createdAt, updatedAt)
}
