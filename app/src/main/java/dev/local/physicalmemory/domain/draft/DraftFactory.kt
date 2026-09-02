package dev.local.physicalmemory.domain.draft

import dev.local.physicalmemory.domain.InventoryRepository
import dev.local.physicalmemory.nlu.*

/** Only reads current state. It cannot confirm a candidate or choose a deletion target. */
class DraftFactory(private val repository: InventoryRepository) {
    suspend fun create(result: NluResult, rawText: String, correctedText: String = rawText): OperationDraft {
        require(result is NluResult.UpsertItemInfo || result is NluResult.ProposeAddUnits)
        val name = when(result) { is NluResult.UpsertItemInfo -> result.item; is NluResult.ProposeAddUnits -> result.item; else -> null }.orEmpty().trim()
        val current = if(name.isBlank()) null else repository.findByName(name)
        val location = when(result) { is NluResult.UpsertItemInfo -> result.location.value; is NluResult.ProposeAddUnits -> result.location; else -> null }
        val base = DraftData(rawText = rawText, correctedText = correctedText, nluResult = result, itemName = name,
            current = current, proposedLocation = location ?: current?.location.orEmpty(), locationExplicit = location != null)
        return when(result) {
            is NluResult.UpsertItemInfo -> if(current == null) CreateItemDraft(base) else UpdateItemDraft(base)
            is NluResult.ProposeAddUnits -> AddUnitsDraft(base.copy(countText = result.count?.toString().orEmpty(),
                unitLabel = result.unitLabel.orEmpty(), units = if(result.count in 1..DraftValidator.MAX_UNITS)
                    List(result.count!!) { DraftUnit(expiryDate =
                        if(Issue.AMBIGUOUS_DATE in result.issues) "" else result.defaultExpiry?.value.orEmpty()) } else emptyList()))
            else -> error("Read actions do not create operation drafts")
        }
    }
    suspend fun changeName(draft: OperationDraft, name: String): OperationDraft {
        val current = if(name.isBlank()) null else repository.findByName(name.trim())
        return draft.withData(draft.data.copy(itemName = name, current = current,
            proposedLocation = if(draft.data.locationExplicit) draft.data.proposedLocation else current?.location.orEmpty()))
    }
    fun changeCount(draft: AddUnitsDraft, text: String): AddUnitsDraft {
        val count = text.toIntOrNull()
        val units = if(count in 1..DraftValidator.MAX_UNITS) List(count!!) { i ->
            draft.data.units.getOrNull(i) ?: DraftUnit()
        } else emptyList()
        return draft.copy(data = draft.data.copy(countText = text, units = units))
    }
    /** Explicit user correction of the draft mode. Preserve edits and the original NLU candidate. */
    fun changeInventoryMode(draft: OperationDraft, addUnits: Boolean): OperationDraft = when {
        addUnits -> AddUnitsDraft(draft.data)
        draft.data.current == null -> CreateItemDraft(draft.data)
        else -> UpdateItemDraft(draft.data)
    }
}
