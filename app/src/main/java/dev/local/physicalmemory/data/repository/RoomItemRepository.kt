package dev.local.physicalmemory.data.repository

import dev.local.physicalmemory.data.database.ItemDao
import dev.local.physicalmemory.domain.ItemRepository
import dev.local.physicalmemory.domain.model.ItemRecord
import dev.local.physicalmemory.domain.model.MAX_ITEM_LENGTH
import dev.local.physicalmemory.domain.model.MAX_LOCATION_LENGTH
import dev.local.physicalmemory.domain.model.normalizeItemText
import kotlinx.coroutines.flow.map

class RoomItemRepository(
    private val dao: ItemDao,
    private val now: () -> Long = System::currentTimeMillis,
) : ItemRepository {
    override fun getRecentItems() = dao.getRecentItems().map { items -> items.map { it.toRecord() } }

    override suspend fun upsertItem(name: String, location: String): ItemRecord {
        val cleanName = normalizeItemText(name)
        val cleanLocation = normalizeItemText(location)
        require(cleanName.isNotBlank() && cleanName.length <= MAX_ITEM_LENGTH)
        require(cleanLocation.isNotBlank() && cleanLocation.length <= MAX_LOCATION_LENGTH)
        return dao.upsertItem(cleanName, cleanLocation, now()).toRecord()
    }

    override suspend fun findItem(name: String): ItemRecord? =
        dao.findItem(normalizeItemText(name))?.toRecord()

    override suspend fun getItemNames() = dao.getItemNames()

    override suspend fun findItemById(id: Long) = dao.findItemById(id)?.toRecord()
}
