package dev.local.physicalmemory.domain.matching

import dev.local.physicalmemory.domain.model.ItemName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Deterministic character matching, not phonetic or semantic understanding. */
class FuzzyItemMatcher(
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ItemMatcher {
    private data class Scored(
        val item: ItemName,
        val similarity: Double,
        val normalizedExact: Boolean = false,
        val canResolve: Boolean = false,
    )

    override suspend fun match(query: String, candidates: List<ItemName>): NameMatch = withContext(dispatcher) {
        val key = normalizedKey(query)
        if (key.isEmpty()) return@withContext NameMatch.None
        val queryPoints = key.codePoints().toArray()
        val scored = candidates.mapNotNull { item ->
            currentCoroutineContext().ensureActive()
            val candidateKey = normalizedKey(item.name)
            val points = candidateKey.codePoints().toArray()
            when {
                key == candidateKey -> Scored(item, 1.0, normalizedExact = true)
                min(points.size, queryPoints.size) < 2 -> null
                // A partial name (钥匙 -> 车钥匙) always needs a choice, even with one candidate.
                key.contains(candidateKey) || candidateKey.contains(key) -> Scored(
                    item, min(points.size, queryPoints.size).toDouble() / max(points.size, queryPoints.size),
                )
                else -> {
                    val budget = if (min(points.size, queryPoints.size) >= 6) 2 else 1
                    if (abs(points.size - queryPoints.size) > budget) null
                    else {
                        val distance = editDistance(queryPoints, points)
                        if (distance > budget) null
                        else Scored(item, 1.0 - distance.toDouble() / max(points.size, queryPoints.size),
                            canResolve = distance == 1 && points.size == queryPoints.size && points.size >= 4)
                    }
                }
            }
        }.sortedWith(compareByDescending<Scored> { it.similarity }.thenBy { it.item.name }.thenBy { it.item.id })

        if (scored.isEmpty()) return@withContext NameMatch.None
        val normalized = scored.filter { it.normalizedExact }
        if (normalized.size == 1) return@withContext NameMatch.Resolved(normalized.single().item)
        if (normalized.size > 1) return@withContext NameMatch.NeedsConfirmation(normalized.take(5).map { it.item })

        val best = scored.first()
        val gap = best.similarity - (scored.getOrNull(1)?.similarity ?: 0.0)
        if (best.canResolve && gap + 1e-9 >= 0.20) NameMatch.Resolved(best.item)
        else NameMatch.NeedsConfirmation(scored.take(5).map { it.item })
    }

    private fun normalizedKey(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).filterNot { it.isWhitespace() || Character.isSpaceChar(it) }

    // Optimal string alignment: insert/delete/substitute plus one adjacent transposition.
    // Code points keep surrogate pairs intact. Three rows bound memory by the name length.
    private fun editDistance(left: IntArray, right: IntArray): Int {
        var previousPrevious = IntArray(right.size + 1)
        var previous = IntArray(right.size + 1) { it }
        for (i in 1..left.size) {
            val current = IntArray(right.size + 1)
            current[0] = i
            for (j in 1..right.size) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1,
                    previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                    current[j] = min(current[j], previousPrevious[j - 2] + 1)
                }
            }
            previousPrevious = previous
            previous = current
        }
        return previous[right.size]
    }
}
