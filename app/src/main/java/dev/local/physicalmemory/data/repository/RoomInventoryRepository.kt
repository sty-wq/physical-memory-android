package dev.local.physicalmemory.data.repository

import androidx.room.withTransaction
import dev.local.physicalmemory.data.database.*
import dev.local.physicalmemory.domain.*
import dev.local.physicalmemory.domain.draft.*
import kotlinx.coroutines.flow.map
import java.security.MessageDigest

class RoomInventoryRepository(private val db: AppDatabase, private val now: () -> Long = System::currentTimeMillis) : InventoryRepository {
    private val dao get() = db.inventoryDao()
    override suspend fun findByName(name: String) = dao.byName(name.trim())?.state()
    override suspend fun findById(id: Long) = dao.byId(id)?.state()
    override fun recent() = dao.recent().map { rows -> rows.map { it.state() } }
    override fun observeAll() = dao.observeAll().map { rows -> rows.map {it.state()} }
    override suspend fun updateItem(draft: ItemEditDraft): ItemState = db.withTransaction {
        require(draft.errors().isEmpty()) {draft.errors().joinToString("；")}
        val current=checkNotNull(findById(draft.original.id)) {"物品已不存在，请返回列表"}
        check(current==draft.original) {"记录已变化，请返回物品卡重新打开调整"}
        val name=draft.name.trim();val location=draft.location.trim()
        check(findByName(name)?.id.let {it==null || it==current.id}) {"已有同名物品，请使用其他名称"}
        val changedUnits=current.units.filter {it.id !in draft.confirmedRemovedUnitIds && it.expiryDate!=draft.expiryDates.getValue(it.id).ifBlank {null}}
        if(name==current.name && location==current.location && changedUnits.isEmpty() &&
            draft.addedUnits.isEmpty() && draft.confirmedRemovedUnitIds.isEmpty()) return@withTransaction current
        val time=maxOf(now(),current.updatedAt+1,(current.units.maxOfOrNull {it.updatedAt} ?: -1)+1)
        check(dao.updateInfo(current.id,name,location,time)==1)
        changedUnits.forEach {unit->check(dao.updateExpiry(unit.id,current.id,draft.expiryDates.getValue(unit.id).ifBlank {null},time)==1)}
        draft.confirmedRemovedUnitIds.forEach {id->check(dao.deleteUnit(id,current.id)==1)}
        dao.insertUnits(draft.addedUnits.map {InventoryUnitEntity(itemId=current.id,
            expiryDate=it.expiryDate.ifBlank {null},createdAt=time,updatedAt=time)})
        checkNotNull(findById(current.id))
    }
    override suspend fun confirm(draft: OperationDraft): Confirmation = db.withTransaction {
        val errors = DraftValidator.errors(draft)
        require(errors.isEmpty()) { errors.joinToString("；") }
        val d = draft.data
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(draft.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        dao.receipt(d.id)?.let { receipt ->
            check(receipt.fingerprint == fingerprint) { "此草稿已确认，请重新解析以添加另一批库存" }
            return@withTransaction Confirmation(checkNotNull(findById(receipt.itemId)), noOp = true, replay = true)
        }
        val current = findByName(d.itemName)
        check(current == d.current) { "记录已变化，请重新解析并核对草稿后确认" }
        val time = maxOf(now(), (current?.updatedAt ?: -1) + 1)
        val location = d.proposedLocation.trim()
        val id = current?.id ?: dao.insertItem(ItemEntity(name = d.itemName.trim(), location = location, createdAt = time, updatedAt = time))
        val changed = current != null && current.location != location
        if(changed) dao.changeLocation(id, location, time)
        if(draft is AddUnitsDraft) dao.insertUnits(d.units.map { InventoryUnitEntity(itemId = id,
            expiryDate = it.expiryDate.ifBlank { null }, createdAt = time, updatedAt = time) })
        dao.insertReceipt(ConfirmedDraftEntity(d.id, fingerprint, id))
        Confirmation(checkNotNull(findById(id)), noOp = current != null && !changed && draft !is AddUnitsDraft)
    }
    override suspend fun deleteInventoryUnit(itemId: Long, selectedUnit: InventoryUnit): ItemState = db.withTransaction {
        val live = dao.unit(selectedUnit.id, itemId)
        check(live?.unit() == selectedUnit) { "这份库存已变化或已删除，请重新查看" }
        check(dao.deleteUnit(selectedUnit.id, itemId) == 1)
        checkNotNull(findById(itemId)) // The parent Item is deliberately retained, including when count becomes zero.
    }
    private fun ItemWithUnits.state() = ItemState(item.id,item.name,item.location,item.lowStockThreshold,item.createdAt,item.updatedAt,
        units.sortedBy { it.id }.map { it.unit() })
    private fun InventoryUnitEntity.unit() = InventoryUnit(id,itemId,expiryDate,createdAt,updatedAt)
}
