package dev.local.physicalmemory.domain.draft

import dev.local.physicalmemory.domain.ItemState
import dev.local.physicalmemory.nlu.NluResult
import java.util.UUID

data class DraftUnit(val key: String = UUID.randomUUID().toString(), val expiryDate: String = "")
data class DraftData(val id: String = UUID.randomUUID().toString(), val rawText: String, val correctedText: String,
    val nluResult: NluResult, val itemName: String, val current: ItemState?, val proposedLocation: String,
    val locationExplicit: Boolean, val countText: String = "", val unitLabel: String = "",
    val units: List<DraftUnit> = emptyList(), val reviewedIssues: Boolean = false)

sealed interface OperationDraft { val data: DraftData }
data class CreateItemDraft(override val data: DraftData) : OperationDraft
data class UpdateItemDraft(override val data: DraftData) : OperationDraft
data class AddUnitsDraft(override val data: DraftData) : OperationDraft

fun OperationDraft.withData(data: DraftData): OperationDraft = when(this) {
    is AddUnitsDraft -> AddUnitsDraft(data)
    else -> if(data.current == null) CreateItemDraft(data) else UpdateItemDraft(data)
}
