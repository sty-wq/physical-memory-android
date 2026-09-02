package dev.local.physicalmemory.domain

import dev.local.physicalmemory.domain.parser.Command
import dev.local.physicalmemory.domain.matching.FuzzyItemMatcher
import dev.local.physicalmemory.domain.matching.ItemMatcher
import dev.local.physicalmemory.domain.matching.NameMatch
import dev.local.physicalmemory.domain.model.ItemName
import dev.local.physicalmemory.domain.model.ItemRecord

data class MemoryResult(val title: String, val text: String, val suggestions: List<ItemName> = emptyList())

/** Device-independent entry point. Voice, manual text, or other adapters can issue commands. */
class PhysicalMemory(
    private val repository: ItemRepository,
    private val matcher: ItemMatcher = FuzzyItemMatcher(),
) {
    fun recentItems() = repository.getRecentItems()

    suspend fun execute(command: Command): MemoryResult = when (command) {
        is Command.Store -> {
            val item = repository.upsertItem(command.item, command.location)
            MemoryResult("已记住", "已记住：${item.name}在${item.location}")
        }
        is Command.Find -> find(command.item)
        Command.Unknown -> MemoryResult(
            "换个说法试试",
            "暂时没听懂，可以试试：\n“钥匙放在玄关柜”\n或\n“钥匙在哪”",
        )
    }

    private suspend fun find(query: String): MemoryResult {
        repository.findItem(query)?.let { return found(it) }
        // Full name catalog, not the 20-row recent list; locations are read only after resolution.
        val names = repository.getItemNames()
        return when (val match = matcher.match(query, names)) {
            NameMatch.None -> missing(query)
            is NameMatch.Resolved -> {
                val id = names.firstOrNull { it.id == match.item.id }?.id ?: return missing(query)
                val record = repository.findItemById(id) ?: return missing(query)
                MemoryResult("找到相近物品", "按相近名称匹配到“${record.name}”\n${record.name}在${record.location}")
            }
            is NameMatch.NeedsConfirmation -> {
                val known = names.associateBy { it.id }
                val choices = match.candidates.mapNotNull { known[it.id] }.distinctBy { it.id }.take(5)
                if (choices.isEmpty()) missing(query)
                else MemoryResult("你要找的是哪件？", "没有完全匹配“$query”的记录，请选择：", choices)
            }
        }
    }

    suspend fun selectItem(id: Long): MemoryResult = repository.findItemById(id)?.let(::found)
        ?: MemoryResult("记录已不可用", "这条记录已不存在，请重新查询。")

    private fun found(item: ItemRecord) = MemoryResult("找到啦", "${item.name}在${item.location}")
    private fun missing(query: String) = MemoryResult("还没找到", "还没有记录“$query”的位置")
}
