package dev.local.physicalmemory.domain.draft

import java.time.LocalDate

object DraftValidator {
    const val MAX_UNITS = 100
    fun errors(draft: OperationDraft): List<String> = buildList {
        val d = draft.data
        if(d.itemName.trim().isEmpty() || d.itemName.trim().length > 80) add("请填写 1–80 字的物品名称")
        if(d.current != null && d.current.name != d.itemName.trim()) add("物品名称已变化，请等待重新核对当前记录")
        if(d.proposedLocation.trim().length > 200) add("位置不能超过 200 字")
        if(d.current?.location?.isNotEmpty() == true && d.proposedLocation.isBlank()) add("已有位置不能清空；请填写位置或保留原值")
        if(d.nluResult.issues.isNotEmpty() && !d.reviewedIssues) add("请检查模型提示并勾选已核对")
        if(draft is AddUnitsDraft) {
            val count = d.countText.toIntOrNull()
            if(count !in 1..MAX_UNITS || count != d.units.size) add("本次添加数量须为 1–$MAX_UNITS，并与实例数一致")
            if(d.unitLabel.length > 16) add("量词不能超过 16 字")
            if(d.units.map { it.key }.distinct().size != d.units.size) add("库存草稿编号重复")
            d.units.forEachIndexed { index, unit ->
                if(unit.expiryDate.isNotBlank() && (!unit.expiryDate.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) ||
                    runCatching { LocalDate.parse(unit.expiryDate) }.isFailure)) add("第 ${index+1} 份日期无效，请使用 YYYY-MM-DD 或留空")
            }
        }
    }
}
