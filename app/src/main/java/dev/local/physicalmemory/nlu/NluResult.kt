package dev.local.physicalmemory.nlu

enum class Issue { MISSING_ITEM, MISSING_COUNT, INVALID_COUNT, INVALID_DATE, AMBIGUOUS_ITEM, AMBIGUOUS_LOCATION, AMBIGUOUS_DATE, UNSUPPORTED_OPERATION }
enum class LocationOp { SET, KEEP }
data class LocationChange(val op: LocationOp, val value: String?)
data class DefaultExpiry(val value: String?, val sourceText: String?)

/** Model vocabulary only. No database identity, entity or repository is visible here. */
sealed interface NluResult {
    val issues: List<Issue>
    data class UpsertItemInfo(val item: String?, val location: LocationChange, override val issues: List<Issue> = emptyList()) : NluResult
    data class ProposeAddUnits(val item: String?, val count: Int?, val unitLabel: String?, val location: String?,
        val defaultExpiry: DefaultExpiry?, override val issues: List<Issue> = emptyList()) : NluResult
    data class OpenItem(val item: String?, override val issues: List<Issue> = emptyList()) : NluResult
    data class Unknown(override val issues: List<Issue> = emptyList()) : NluResult
}
