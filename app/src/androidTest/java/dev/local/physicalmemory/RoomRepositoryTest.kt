package dev.local.physicalmemory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomItemRepository
import dev.local.physicalmemory.domain.PhysicalMemory
import dev.local.physicalmemory.domain.parser.Command
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: RoomItemRepository
    private var now = 1_000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        repository = RoomItemRepository(database.itemDao()) { now }
    }

    @After fun tearDown() { database.close() }

    @Test fun insertAndFindRetainAllMetadata() = runBlocking {
        val created = repository.upsertItem("钥匙", "玄关柜")
        assertTrue(created.id > 0)
        assertEquals(1_000L, created.createdAt)
        assertEquals(1_000L, created.updatedAt)
        assertEquals(created, repository.findItem("钥匙"))
    }

    @Test fun missingItemReturnsNull() = runBlocking {
        assertNull(repository.findItem("护照"))
    }

    @Test fun updatePreservesIdentityAndCreationTime() = runBlocking {
        val first = repository.upsertItem("钥匙", "玄关柜")
        now = 2_000
        val updated = repository.upsertItem("钥匙", "书桌上")
        assertEquals(first.id, updated.id)
        assertEquals(first.createdAt, updated.createdAt)
        assertEquals(2_000L, updated.updatedAt)
        assertEquals("书桌上", repository.findItem("钥匙")?.location)
        assertEquals(1, repository.getRecentItems().first().size)
    }

    @Test fun lookupDoesNotModifyAnyColumn() = runBlocking {
        val before = repository.upsertItem("钥匙", "玄关柜")
        now = 9_000
        repeat(3) { assertEquals(before, repository.findItem("钥匙")) }
        assertEquals(before, repository.getRecentItems().first().single())
    }

    @Test fun recentOrderChangesAfterLocationUpdate() = runBlocking {
        repository.upsertItem("钥匙", "玄关柜")
        now = 2_000; repository.upsertItem("护照", "抽屉")
        now = 3_000; repository.upsertItem("相机", "书包")
        assertEquals(listOf("相机", "护照", "钥匙"), repository.getRecentItems().first().map { it.name })
        now = 4_000; repository.upsertItem("钥匙", "书桌")
        assertEquals(listOf("钥匙", "相机", "护照"), repository.getRecentItems().first().map { it.name })
    }

    @Test fun recentListIsLimitedToTwentyWithoutDeletingOlderItems() = runBlocking {
        repeat(25) { index -> now = index.toLong(); repository.upsertItem("物品$index", "位置$index") }
        val recent = repository.getRecentItems().first()
        assertEquals(20, recent.size)
        assertEquals("物品24", recent.first().name)
        assertEquals("物品5", recent.last().name)
        assertNotNull(repository.findItem("物品0"))
    }

    @Test fun fuzzyLookupSearchesBeyondRecentTwentyAndPreservesEveryColumn() = runBlocking {
        val medicine = repository.upsertItem("连花清瘟", "放药的柜子里")
        repeat(25) { index -> now += 1; repository.upsertItem("物品$index", "位置$index") }
        assertFalse(repository.getRecentItems().first().any { it.id == medicine.id })
        val before = repository.getItemNames().map { repository.findItemById(it.id) }
        assertEquals(26, before.size)
        assertEquals("按相近名称匹配到“连花清瘟”\n连花清瘟在放药的柜子里",
            PhysicalMemory(repository).execute(Command.Find("莲花清瘟")).text)
        assertEquals(before, repository.getItemNames().map { repository.findItemById(it.id) })
        assertEquals(medicine, repository.findItemById(medicine.id))
        assertNull(repository.findItemById(-1))
    }

    @Test fun normalizedAmbiguityStillPrefersAnExactStoredName() = runBlocking {
        repository.upsertItem("SD卡", "相机包")
        repository.upsertItem("sd卡", "抽屉")
        val memory = PhysicalMemory(repository)
        assertEquals("SD卡在相机包", memory.execute(Command.Find("SD卡")).text)
        assertEquals(2, memory.execute(Command.Find("ｓｄ卡")).suggestions.size)
    }

    @Test fun normalizationUsesTheSameKeyForStoreAndFind() = runBlocking {
        val first = repository.upsertItem(" 钥 匙　", " 玄关 柜 ")
        val second = repository.upsertItem("钥匙", "书桌上")
        assertEquals(first.id, second.id)
        assertEquals(second, repository.findItem(" 钥 匙 "))
        assertEquals(1, repository.getRecentItems().first().size)
    }

    @Test fun concurrentWritesHaveOneIdentityAndMonotonicUpdateTime() = runBlocking {
        val saved = coroutineScope {
            (1..20).map { index -> async(Dispatchers.IO) {
                repository.upsertItem("钥匙", "位置$index")
            } }.awaitAll()
        }
        assertEquals(1, saved.map { it.id }.distinct().size)
        val final = repository.getRecentItems().first().single()
        assertEquals(1_000L, final.createdAt)
        assertEquals(1_019L, final.updatedAt)
        assertTrue(final.location in (1..20).map { "位置$it" })
    }

    @Test fun flowEmitsUpdatedLocation() = runBlocking {
        repository.upsertItem("钥匙", "玄关柜")
        val changed = async {
            withTimeout(5_000) {
                repository.getRecentItems().first { it.singleOrNull()?.location == "书桌上" }
            }
        }
        now = 2_000; repository.upsertItem("钥匙", "书桌上")
        assertEquals("书桌上", changed.await().single().location)
    }

    @Test fun diskDatabaseSurvivesClosingAndReopening() = runBlocking {
        val testName = "room-persistence-test.db"
        context.deleteDatabase(testName)
        try {
            val firstDb = Room.databaseBuilder(context, AppDatabase::class.java, testName).build()
            val saved = try { RoomItemRepository(firstDb.itemDao()).upsertItem("钥匙", "玄关柜") }
                finally { firstDb.close() }
            val reopened = Room.databaseBuilder(context, AppDatabase::class.java, testName).build()
            try { assertEquals(saved, RoomItemRepository(reopened.itemDao()).findItem("钥匙")) }
            finally { reopened.close() }
        } finally { context.deleteDatabase(testName) }
    }

    @Test fun blankFieldsAreRejectedWithoutWrites() = runBlocking {
        try { repository.upsertItem(" ", "柜子"); fail("blank name accepted") }
        catch (_: IllegalArgumentException) { }
        try { repository.upsertItem("钥匙", " "); fail("blank location accepted") }
        catch (_: IllegalArgumentException) { }
        assertTrue(repository.getRecentItems().first().isEmpty())
    }
}
