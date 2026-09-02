package dev.local.physicalmemory.domain

import dev.local.physicalmemory.domain.draft.OperationDraft
import dev.local.physicalmemory.domain.draft.ItemEditDraft
import kotlinx.coroutines.flow.Flow

data class InventoryUnit(val id: Long, val itemId: Long, val expiryDate: String?, val createdAt: Long, val updatedAt: Long)
data class ItemState(val id: Long, val name: String, val location: String, val lowStockThreshold: Int,
    val createdAt: Long, val updatedAt: Long, val units: List<InventoryUnit>) {
    val quantity: Int get() = units.size
}
data class Confirmation(val item: ItemState, val noOp: Boolean, val replay: Boolean = false)

interface InventoryRepository {
    suspend fun findByName(name: String): ItemState?
    suspend fun findById(id: Long): ItemState?
    fun recent(): Flow<List<ItemState>>
    fun observeAll(): Flow<List<ItemState>>
    suspend fun updateItem(draft: ItemEditDraft): ItemState
    suspend fun confirm(draft: OperationDraft): Confirmation
    suspend fun deleteInventoryUnit(itemId: Long, selectedUnit: InventoryUnit): ItemState
}
