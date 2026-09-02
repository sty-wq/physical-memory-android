package dev.local.physicalmemory

import dev.local.physicalmemory.domain.ItemRepository
import dev.local.physicalmemory.domain.PhysicalMemory
import dev.local.physicalmemory.domain.matching.ItemMatcher
import dev.local.physicalmemory.domain.matching.NameMatch
import dev.local.physicalmemory.domain.model.ItemName
import dev.local.physicalmemory.domain.model.ItemRecord
import dev.local.physicalmemory.domain.parser.Command
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PhysicalMemoryMatchingTest {
    private val record = ItemRecord(1, "连花清瘟", "药柜", 1, 2)
    private val repository = object : ItemRepository {
        override fun getRecentItems() = flowOf(listOf(record))
        override suspend fun findItem(name: String) = record.takeIf { it.name == name }
        override suspend fun getItemNames() = listOf(ItemName(record.id, record.name))
        override suspend fun findItemById(id: Long) = record.takeIf { it.id == id }
        override suspend fun upsertItem(name: String, location: String): ItemRecord = error("Queries must not write")
    }
    private fun matcher(result: NameMatch) = object : ItemMatcher {
        override suspend fun match(query: String, candidates: List<ItemName>) = result
    }

    @Test fun exactLookupNeverInvokesFallback() = runBlocking {
        val memory = PhysicalMemory(repository, object : ItemMatcher {
            override suspend fun match(query: String, candidates: List<ItemName>): NameMatch = error("Exact match must win")
        })
        assertEquals("连花清瘟在药柜", memory.execute(Command.Find("连花清瘟")).text)
    }
    @Test fun replacementStrategyReturnsOnlyPersistedNameAndLocation() = runBlocking {
        val memory = PhysicalMemory(repository, matcher(NameMatch.Resolved(ItemName(1, "untrusted generated name"))))
        assertEquals("按相近名称匹配到“连花清瘟”\n连花清瘟在药柜", memory.execute(Command.Find("semantic query")).text)
    }
    @Test fun nonexistentStrategyIdDoesNotProduceAnAnswer() = runBlocking {
        val memory = PhysicalMemory(repository, matcher(NameMatch.Resolved(ItemName(999, "虚构物品"))))
        assertEquals("还没找到", memory.execute(Command.Find("药品")).title)
    }
    @Test fun suggestionsAreValidatedAndDeduplicated() = runBlocking {
        val memory = PhysicalMemory(repository, matcher(NameMatch.NeedsConfirmation(listOf(
            ItemName(999, "虚构物品"), ItemName(1, "changed"), ItemName(1, "duplicated")))))
        assertEquals(listOf(ItemName(1, "连花清瘟")), memory.execute(Command.Find("药品")).suggestions)
    }
}
