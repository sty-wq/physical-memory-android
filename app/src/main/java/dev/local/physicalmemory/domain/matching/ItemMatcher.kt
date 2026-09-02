package dev.local.physicalmemory.domain.matching

import dev.local.physicalmemory.domain.model.ItemName

sealed interface NameMatch {
    data object None : NameMatch
    data class Resolved(val item: ItemName) : NameMatch
    data class NeedsConfirmation(val candidates: List<ItemName>) : NameMatch
}

/** Replaceable retrieval strategy: a later semantic implementation returns existing item IDs. */
interface ItemMatcher {
    suspend fun match(query: String, candidates: List<ItemName>): NameMatch
}
