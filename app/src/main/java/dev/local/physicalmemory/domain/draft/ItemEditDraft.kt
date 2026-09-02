package dev.local.physicalmemory.domain.draft

import dev.local.physicalmemory.domain.ItemState
import java.time.LocalDate

/** All changes stay in this draft until save. Deletions require selecting and confirming each unit. */
data class ItemEditDraft(val original: ItemState, val name: String = original.name,
    val location: String = original.location,
    val expiryDates: Map<Long,String> = original.units.associate {it.id to it.expiryDate.orEmpty()},
    val addedCountText: String = "0", val addedUnits: List<DraftUnit> = emptyList(),
    val confirmedRemovedUnitIds: Set<Long> = emptySet()) {
    val quantity: Int get() = original.quantity - confirmedRemovedUnitIds.size + addedUnits.size

    fun withAddedCount(text: String): ItemEditDraft {
        val count = text.toIntOrNull()?.takeIf {it in 0..DraftValidator.MAX_UNITS}
        return copy(addedCountText=text, addedUnits=if(count==null) addedUnits else
            List(count) {index -> addedUnits.getOrNull(index) ?: DraftUnit()})
    }

    fun errors(): List<String> = buildList {
        if(name.trim().length !in 1..80) add("请填写 1–80 字的物品名称")
        if(location.trim().length > 200) add("位置不能超过 200 字")
        if(original.location.isNotBlank() && location.isBlank()) add("请填写位置或保留原值")
        if(expiryDates.keys != original.units.map {it.id}.toSet()) add("库存已变化，请重新打开物品卡")
        if(!original.units.map {it.id}.toSet().containsAll(confirmedRemovedUnitIds)) add("只能删除所选物品的库存")
        val count=addedCountText.toIntOrNull()
        if(count==null || count !in 0..DraftValidator.MAX_UNITS || count!=addedUnits.size)
            add("新增数量请填写 0–${DraftValidator.MAX_UNITS} 的整数")
        if(addedUnits.map {it.key}.toSet().size!=addedUnits.size) add("新增库存重复，请重新调整数量")
        val dates=expiryDates.filterKeys {it !in confirmedRemovedUnitIds}.values + addedUnits.map {it.expiryDate}
        if(dates.any {it.isNotBlank() && (!it.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) || runCatching {LocalDate.parse(it)}.isFailure)})
            add("日期无效，请重新选择日期")
    }
}
