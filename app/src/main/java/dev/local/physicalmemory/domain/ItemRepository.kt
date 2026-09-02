package dev.local.physicalmemory.domain

import dev.local.physicalmemory.domain.model.ItemRecord
import dev.local.physicalmemory.domain.model.ItemName
import kotlinx.coroutines.flow.Flow

interface ItemRepository {
    fun getRecentItems(): Flow<List<ItemRecord>>
    suspend fun upsertItem(name: String, location: String): ItemRecord
    suspend fun findItem(name: String): ItemRecord?
    suspend fun getItemNames(): List<ItemName>
    suspend fun findItemById(id: Long): ItemRecord?
}
