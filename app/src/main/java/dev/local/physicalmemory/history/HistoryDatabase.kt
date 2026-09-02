package dev.local.physicalmemory.history

import androidx.room.*
import kotlinx.coroutines.flow.map

/** A small completion log, separate from the unchanged inventory database. Never a state snapshot. */
@Entity(tableName="history")
data class HistoryEntity(@PrimaryKey val operationKey: String, val itemId: Long, val itemName: String,
    val summary: String, val completedAt: Long)
@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY completedAt DESC, operationKey DESC")
    fun observe(): kotlinx.coroutines.flow.Flow<List<HistoryEntity>>
    @Insert(onConflict=OnConflictStrategy.IGNORE) suspend fun insert(entry: HistoryEntity)
}
@Database(entities=[HistoryEntity::class],version=1,exportSchema=true)
abstract class HistoryDatabase: RoomDatabase() { abstract fun historyDao(): HistoryDao }
class RoomHistoryStore(private val dao: HistoryDao): HistoryStore {
    override fun observe()=dao.observe().map { rows -> rows.map { HistoryRecord(it.operationKey,it.itemId,it.itemName,it.summary,it.completedAt) } }
    override suspend fun append(record: HistoryRecord) { dao.insert(HistoryEntity(record.key,record.itemId,record.itemName,record.summary,record.completedAt)) }
}
