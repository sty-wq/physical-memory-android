package dev.local.physicalmemory

import androidx.lifecycle.ViewModelStore
import dev.local.physicalmemory.domain.ItemRepository
import dev.local.physicalmemory.domain.PhysicalMemory
import dev.local.physicalmemory.domain.matching.FuzzyItemMatcher
import dev.local.physicalmemory.domain.model.ItemName
import dev.local.physicalmemory.domain.model.ItemRecord
import dev.local.physicalmemory.ui.home.HomeViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var repository: FakeItemRepository
    private lateinit var model: HomeViewModel
    private val store = ViewModelStore()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        repository = FakeItemRepository()
        model = HomeViewModel(PhysicalMemory(repository, FuzzyItemMatcher(dispatcher)))
        store.put("home", model)
    }

    @After fun tearDown() { store.clear(); Dispatchers.resetMain() }

    @Test fun storeClearsInputAndRefreshesRecentItems() = runTest {
        model.onInputChanged("钥匙放在玄关柜")
        model.submit()
        advanceUntilIdle()
        assertEquals("已记住：钥匙在玄关柜", model.state.value.result?.text)
        assertEquals("", model.state.value.input)
        assertEquals("玄关柜", model.state.value.recentItems.single().location)
        assertFalse(model.state.value.isSubmitting)
    }

    @Test fun findIsReadOnlyAndKeepsInput() = runTest {
        repository.upsertItem("钥匙", "玄关柜")
        val before = repository.items.value
        model.onInputChanged("钥匙在哪")
        model.submit(); advanceUntilIdle()
        assertEquals("钥匙在玄关柜", model.state.value.result?.text)
        assertEquals("钥匙在哪", model.state.value.input)
        assertEquals(before, repository.items.value)
        assertEquals(1, repository.writeCalls)
    }

    @Test fun plainLocationStatementKeepsTheWholeLocationAndCanBeFound() = runTest {
        model.onInputChanged("连花清瘟在放药的柜子里")
        model.submit(); advanceUntilIdle()
        assertEquals("已记住：连花清瘟在放药的柜子里", model.state.value.result?.text)
        val record = model.state.value.recentItems.single()
        assertEquals("连花清瘟", record.name)
        assertEquals("放药的柜子里", record.location)
        assertEquals("", model.state.value.input)
        model.onInputChanged("连花清瘟在哪")
        model.submit(); advanceUntilIdle()
        assertEquals("连花清瘟在放药的柜子里", model.state.value.result?.text)
        assertEquals(record, model.state.value.recentItems.single())
        assertEquals(1, repository.writeCalls)
    }

    @Test fun unsupportedYesNoQuestionDoesNotStoreAPosition() = runTest {
        model.onInputChanged("钥匙在抽屉里吗")
        model.submit(); advanceUntilIdle()
        assertEquals("换个说法试试", model.state.value.result?.title)
        assertEquals(0, repository.writeCalls)
    }

    @Test fun findMissingReportsSpecificItem() = runTest {
        model.onInputChanged("护照在哪")
        model.submit(); advanceUntilIdle()
        assertEquals("还没有记录“护照”的位置", model.state.value.result?.text)
        assertEquals(0, repository.writeCalls)
    }

    @Test fun unknownShowsExamplesWithoutDatabaseOperations() = runTest {
        model.onInputChanged("随便聊聊")
        model.submit(); advanceUntilIdle()
        assertTrue(model.state.value.result!!.text.contains("暂时没听懂"))
        assertTrue(model.state.value.result!!.text.contains("钥匙放在玄关柜"))
        assertEquals(0, repository.writeCalls + repository.readCalls)
    }

    @Test fun emptyInputCannotSubmit() = runTest {
        model.onInputChanged(" 　\t")
        model.submit(); advanceUntilIdle()
        assertFalse(model.state.value.canSubmit)
        assertNull(model.state.value.result)
        assertEquals(0, repository.writeCalls + repository.readCalls)
    }

    @Test fun doubleSubmitIsIgnoredWhileSaving() = runTest {
        repository.gate = CompletableDeferred()
        model.onInputChanged("钥匙放在玄关柜")
        model.submit(); model.submit(); runCurrent()
        assertTrue(model.state.value.isSubmitting)
        assertFalse(model.state.value.canSubmit)
        assertEquals(1, repository.writeCalls)
        model.onInputChanged("不可覆盖进行中的输入")
        assertEquals("钥匙放在玄关柜", model.state.value.input)
        repository.gate!!.complete(Unit); advanceUntilIdle()
        assertFalse(model.state.value.isSubmitting)
    }

    @Test fun storageFailurePreservesDraftAndAllowsRetry() = runTest {
        repository.writeFailure = IllegalStateException("database unavailable")
        model.onInputChanged("钥匙放在玄关柜")
        model.submit(); advanceUntilIdle()
        assertEquals("操作未完成", model.state.value.result?.title)
        assertEquals("钥匙放在玄关柜", model.state.value.input)
        assertTrue(model.state.value.canSubmit)
        repository.writeFailure = null
        model.submit(); advanceUntilIdle()
        assertEquals("已记住", model.state.value.result?.title)
    }

    @Test fun updatingAnItemThenFindingReturnsNewLocation() = runTest {
        model.onInputChanged("钥匙放在玄关柜"); model.submit(); advanceUntilIdle()
        model.onInputChanged("钥匙放在书桌上"); model.submit(); advanceUntilIdle()
        model.onInputChanged("钥匙在哪"); model.submit(); advanceUntilIdle()
        assertEquals("钥匙在书桌上", model.state.value.result?.text)
        assertEquals(1, model.state.value.recentItems.size)
    }

    @Test fun recentReadErrorHasRetryAndRecovers() = runTest {
        repository.observeFailure = true
        model.reloadRecords()
        advanceUntilIdle()
        assertNotNull(model.state.value.recordsError)
        assertFalse(model.state.value.isLoadingRecords)
        repository.observeFailure = false
        model.reloadRecords(); advanceUntilIdle()
        assertNull(model.state.value.recordsError)
        assertFalse(model.state.value.isLoadingRecords)
    }

    @Test fun typoQueryReturnsCanonicalNameWithoutWriting() = runTest {
        repository.upsertItem("连花清瘟", "放药的柜子里")
        val before = repository.items.value
        model.onInputChanged("莲花清瘟在哪"); model.submit(); advanceUntilIdle()
        assertEquals("找到相近物品", model.state.value.result?.title)
        assertEquals("按相近名称匹配到“连花清瘟”\n连花清瘟在放药的柜子里", model.state.value.result?.text)
        assertEquals(before, repository.items.value)
        assertEquals(1, repository.writeCalls)
    }

    @Test fun candidateSelectionReadsLatestLocationAndIsReadOnly() = runTest {
        val item = repository.upsertItem("车钥匙", "玄关")
        repository.upsertItem("家钥匙", "包里")
        model.onInputChanged("钥匙在哪"); model.submit(); advanceUntilIdle()
        assertEquals(2, model.state.value.result?.suggestions?.size)
        repository.upsertItem("车钥匙", "书桌")
        val before = repository.items.value
        model.selectCandidate(item.id); advanceUntilIdle()
        assertEquals("车钥匙在书桌", model.state.value.result?.text)
        assertTrue(model.state.value.result!!.suggestions.isEmpty())
        assertEquals(before, repository.items.value)
        assertEquals(3, repository.writeCalls)
    }

    @Test fun unknownOrStaleCandidateIsIgnored() = runTest {
        val item = repository.upsertItem("车钥匙", "玄关")
        model.onInputChanged("钥匙在哪"); model.submit(); advanceUntilIdle()
        val reads = repository.readCalls
        model.selectCandidate(999); advanceUntilIdle()
        assertEquals(reads, repository.readCalls)
        model.onInputChanged("护照在哪")
        assertNull(model.state.value.result)
        model.selectCandidate(item.id); advanceUntilIdle()
        assertEquals(reads, repository.readCalls)
    }

    @Test fun duplicateCandidateTapIsIgnoredAndReadFailureCanRetry() = runTest {
        val item = repository.upsertItem("车钥匙", "玄关")
        model.onInputChanged("钥匙在哪"); model.submit(); advanceUntilIdle()
        repository.readGate = CompletableDeferred()
        repository.readFailure = IllegalStateException("read failed")
        val reads = repository.readCalls
        model.selectCandidate(item.id); model.selectCandidate(item.id); runCurrent()
        assertTrue(model.state.value.isSubmitting)
        assertEquals(reads + 1, repository.readCalls)
        repository.readGate!!.complete(Unit); advanceUntilIdle()
        assertFalse(model.state.value.isSubmitting)
        assertEquals(1, model.state.value.result?.suggestions?.size)
        repository.readFailure = null
        model.selectCandidate(item.id); advanceUntilIdle()
        assertEquals("车钥匙在玄关", model.state.value.result?.text)
    }

    @Test fun deletedCandidateDoesNotReturnStaleLocation() = runTest {
        val item = repository.upsertItem("车钥匙", "玄关")
        model.onInputChanged("钥匙在哪"); model.submit(); advanceUntilIdle()
        repository.items.value = emptyList()
        model.selectCandidate(item.id); advanceUntilIdle()
        assertEquals("记录已不可用", model.state.value.result?.title)
        assertTrue(model.state.value.result!!.suggestions.isEmpty())
    }

    @Test fun typoStoreDoesNotMergeDifferentNames() = runTest {
        repository.upsertItem("连花清瘟", "药柜")
        model.onInputChanged("莲花清瘟在抽屉"); model.submit(); advanceUntilIdle()
        assertEquals(2, repository.items.value.size)
        assertEquals("药柜", repository.findItem("连花清瘟")?.location)
        model.onInputChanged("莲花清瘟在哪"); model.submit(); advanceUntilIdle()
        assertEquals("找到啦", model.state.value.result?.title)
        assertEquals("莲花清瘟在抽屉", model.state.value.result?.text)
    }

    @Test fun cancellationIsNotPresentedAsStorageFailure() = runTest {
        repository.writeFailure = CancellationException("scope cancelled")
        model.onInputChanged("钥匙放在玄关柜")
        model.submit(); advanceUntilIdle()
        assertNull(model.state.value.result)
        assertEquals("钥匙放在玄关柜", model.state.value.input)
        assertFalse(model.state.value.isSubmitting)
    }
}

private class FakeItemRepository : ItemRepository {
    val items = MutableStateFlow<List<ItemRecord>>(emptyList())
    var writeCalls = 0
    var readCalls = 0
    var writeFailure: Exception? = null
    var observeFailure = false
    var gate: CompletableDeferred<Unit>? = null

    override fun getRecentItems(): Flow<List<ItemRecord>> = flow {
        if (observeFailure) error("read failed")
        emitAll(items)
    }

    override suspend fun upsertItem(name: String, location: String): ItemRecord {
        writeCalls++
        gate?.await()
        writeFailure?.let { throw it }
        val previous = items.value.find { it.name == name }
        val result = ItemRecord(previous?.id ?: writeCalls.toLong(), name, location,
            previous?.createdAt ?: writeCalls.toLong(), writeCalls.toLong())
        items.value = (items.value.filterNot { it.name == name } + result).sortedByDescending { it.updatedAt }
        return result
    }

    override suspend fun findItem(name: String): ItemRecord? {
        readCalls++
        return items.value.find { it.name == name }
    }

    override suspend fun getItemNames(): List<ItemName> {
        readCalls++
        return items.value.map { ItemName(it.id, it.name) }
    }

    override suspend fun findItemById(id: Long): ItemRecord? {
        readCalls++
        readGate?.await()
        readFailure?.let { throw it }
        return items.value.find { it.id == id }
    }

    var readGate: CompletableDeferred<Unit>? = null
    var readFailure: Exception? = null
}
