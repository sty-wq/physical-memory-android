package dev.local.physicalmemory.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

data class HistoryRecord(val key: String, val itemId: Long, val itemName: String, val summary: String, val completedAt: Long)
interface HistoryStore {
    fun observe(): Flow<List<HistoryRecord>>
    suspend fun append(record: HistoryRecord)
}
class InMemoryHistoryStore : HistoryStore {
    private val rows=MutableStateFlow<List<HistoryRecord>>(emptyList())
    override fun observe()=rows
    override suspend fun append(record: HistoryRecord) { rows.update { existing ->
        if(existing.any { it.key==record.key }) existing else (existing+record).sortedByDescending { it.completedAt }
    } }
}
