package dev.local.physicalmemory

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.draft.DraftFactory
import dev.local.physicalmemory.history.InMemoryHistoryStore
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

class StoredItemsUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun tag(s:String)=compose.onNodeWithTag(s)
    private fun exists(s:String)=compose.onAllNodesWithTag(s).fetchSemanticsNodes().isNotEmpty()
    private fun scroll(s:String):SemanticsNodeInteraction {
        val container=when {exists("item-detail-sheet")->"detail-list";exists("item-edit-screen")->"item-edit-screen";exists("draft-screen")->"draft-screen";else->"items-screen"}
        tag(container).performScrollToNode(hasTestTag(s));return tag(s).performScrollTo()
    }
    private fun shot(name:String) {
        compose.waitForIdle()
        inst.uiAutomation.takeScreenshot()?.let {b->File(inst.targetContext.filesDir,"items-$name.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    }
    @Test fun editScreenCanAddFromZeroChangeDatesAndRemoveSelectedStock() {
        val ctx=inst.targetContext;val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build()
        val repo=RoomInventoryRepository(db);val factory=DraftFactory(repo);val nlu=FakeNluEngine {_,_->NluResult.Unknown()}
        val history=InMemoryHistoryStore();val store=ViewModelStore();lateinit var vm:InventoryViewModel
        val original=runBlocking {repo.confirm(factory.create(NluResult.UpsertItemInfo("牛奶",LocationChange(LocationOp.SET,"冰箱")),"isolated fixture")).item}
        inst.runOnMainSync {vm=InventoryViewModel(repo,nlu,historyStore=history);store.put("edit-stock",vm)}
        V2ValidationActivity.factory={vm}
        fun edit() {scroll("edit-item-info").performClick();compose.waitUntil(5000) {exists("item-edit-screen")}}
        fun save() {scroll("save-item-edit").performClick();compose.waitUntil(5000) {!vm.state.value.busy && vm.state.value.itemEdit==null}}
        fun pick(target:String,day:Int) {
            scroll(target).performClick();compose.waitUntil(5000) {exists("expiry-date-page")}
            compose.onNode(calendarDay(day)).performScrollTo().performClick();tag("confirm-expiry-date").performClick()
        }
        fun current()=runBlocking {repo.findById(original.id)}!!
        try {
            inst.uiAutomation.executeShellCommand("am start -W -n ${ctx.packageName}/.V2ValidationActivity").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            compose.waitUntil(15000) {exists("tab-items")};tag("tab-items").performClick()
            compose.waitUntil(5000) {vm.items.value.rows.size==1};scroll("stored-item-${original.id}").performClick()
            compose.waitUntil(5000) {exists("item-detail-sheet")};edit()
            scroll("edit-stock-empty").assertExists();scroll("edit-added-count").performTextReplacement("3")
            pick("edit-added-expiry-1",15);pick("edit-added-expiry-2",18)
            scroll("edit-added-count").performTextReplacement("2")
            scroll("edit-added-count").performTextReplacement("3")
            scroll("edit-added-expiry-1").assertTextContains(LocalDate.now().withDayOfMonth(15).toString(),substring=true)
            assertEquals(original,current())
            scroll("cancel-item-edit").performClick();compose.waitUntil(5000) {exists("item-detail-sheet")}
            assertEquals(original,current());edit()
            scroll("edit-added-count").performTextReplacement("101")
            scroll("save-item-edit").assertIsNotEnabled();assertEquals(original,current())
            scroll("edit-added-count").performTextReplacement("3")
            pick("edit-added-expiry-1",15);pick("edit-added-expiry-2",18)
            scroll("edit-stock-total").assertTextEquals("调整后库存：3 份");shot("edit-stock-add")
            save();val stocked=current();assertEquals(3,stocked.quantity);assertEquals(original.id,stocked.id)
            assertEquals(LocalDate.now().withDayOfMonth(15).toString(),stocked.units[0].expiryDate)
            assertEquals(LocalDate.now().withDayOfMonth(18).toString(),stocked.units[1].expiryDate);assertNull(stocked.units[2].expiryDate)
            edit();val removedId=stocked.units[0].id
            scroll("edit-delete-unit-$removedId").performClick();tag("edit-delete-confirmation").assertExists()
            tag("cancel-edit-delete").performClick();assertTrue(vm.state.value.itemEdit!!.confirmedRemovedUnitIds.isEmpty())
            scroll("edit-delete-unit-$removedId").performClick();tag("confirm-edit-delete").performClick()
            scroll("edit-stock-total").assertTextEquals("调整后库存：2 份");assertEquals(stocked,current())
            scroll("undo-edit-delete-$removedId").performClick();assertTrue(vm.state.value.itemEdit!!.confirmedRemovedUnitIds.isEmpty())
            scroll("edit-delete-unit-$removedId").performClick();tag("confirm-edit-delete").performClick()
            scroll("edit-added-count").performTextReplacement("2")
            scroll("cancel-item-edit").performClick();compose.waitUntil(5000) {exists("item-detail-sheet")}
            assertEquals(stocked,current());edit()
            scroll("edit-delete-unit-$removedId").performClick();tag("confirm-edit-delete").performClick()
            scroll("edit-added-count").performTextReplacement("2");pick("edit-added-expiry-1",20)
            pick("edit-stored-expiry-${stocked.units[1].id}",22)
            scroll("edit-stored-expiry-${stocked.units[1].id}").performClick();tag("clear-expiry-date").performClick()
            scroll("edit-stock-total").assertTextEquals("调整后库存：4 份");shot("edit-stock-mixed")
            assertEquals(stocked,current());save();val changed=current()
            assertEquals(4,changed.quantity);assertFalse(changed.units.any {it.id==removedId})
            assertNull(changed.units[0].expiryDate);assertEquals(stocked.units[1].id,changed.units[0].id)
            assertEquals(stocked.units[2],changed.units[1]);assertEquals(LocalDate.now().withDayOfMonth(20).toString(),changed.units[2].expiryDate)
            edit()
            for(unit in changed.units) {scroll("edit-delete-unit-${unit.id}").performClick();tag("confirm-edit-delete").performClick()}
            scroll("edit-stock-total").assertTextEquals("调整后库存：0 份");assertEquals(changed,current());save()
            assertEquals(0,current().quantity);assertEquals(original.id,current().id)
            compose.waitUntil(5000) {vm.items.value.rows.single().quantity==0}
            assertEquals(0,nlu.calls)
            File(ctx.filesDir,"edit-stock-validation.json").writeText(JSONObject().put("isolatedDb",true)
                .put("addFromZeroWithPerUnitDates",true).put("invalidCountBlocked",true).put("cancelNoWrites",true)
                .put("selectedDeletionConfirmedAndUndoable",true).put("stockAndDatesSavedTogether",true)
                .put("clearExpiry",true).put("zeroStockItemRetained",true).put("nluCalls",nlu.calls).toString(2))
        } finally {V2ValidationActivity.factory=null;inst.runOnMainSync {store.clear()};db.close()}
    }
    @Test fun allItemsEditAddDeleteAndHistoryUseTheSameItemCard() {
        val ctx=inst.targetContext;val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build()
        val repo=RoomInventoryRepository(db);val factory=DraftFactory(repo);val nlu=FakeNluEngine {_,_->NluResult.Unknown()}
        val history=InMemoryHistoryStore();val store=ViewModelStore();lateinit var vm:InventoryViewModel
        inst.runOnMainSync {vm=InventoryViewModel(repo,nlu,historyStore=history);store.put("items",vm)}
        V2ValidationActivity.factory={vm}
        try {
            inst.uiAutomation.executeShellCommand("am start -W -n ${ctx.packageName}/.V2ValidationActivity").use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}
            compose.waitUntil(15000) {exists("tab-items")}
            tag("tab-items").performClick();compose.waitUntil(5000) {exists("items-empty")};shot("empty")
            val original=runBlocking {
                val milk=repo.confirm(factory.create(NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱",null),"isolated fixture")).item
                repeat(24) {repo.confirm(factory.create(NluResult.UpsertItemInfo("物品$it",LocationChange(LocationOp.KEEP,null)),"fixture"))}
                milk
            }
            compose.waitUntil(5000) {vm.items.value.rows.size==25}
            tag("items-count").assertTextEquals("共 25 件");shot("all")
            scroll("stored-item-${original.id}").performClick();compose.waitUntil(5000) {vm.state.value.detail?.id==original.id}
            scroll("detail-quantity").assertTextContains("3",substring=true);shot("card")
            scroll("edit-item-info").performClick();compose.waitUntil(5000) {exists("item-edit-screen")}
            scroll("edit-stored-name").performTextReplacement("不会保存")
            scroll("cancel-item-edit").performClick();compose.waitUntil(5000) {exists("item-detail-sheet")}
            assertEquals(original,runBlocking {repo.findById(original.id)})
            scroll("edit-item-info").performClick();compose.waitUntil(5000) {exists("item-edit-screen")}
            scroll("edit-stored-name").performTextReplacement("物品0")
            scroll("save-item-edit").performClick();compose.waitUntil(5000) {!vm.state.value.busy && vm.state.value.message!=null}
            scroll("item-edit-message").assertTextContains("同名",substring=true)
            assertEquals(original,runBlocking {repo.findById(original.id)})
            scroll("edit-stored-name").performTextReplacement("早餐牛奶")
            scroll("edit-stored-location").performTextReplacement("厨房")
            scroll("edit-stored-expiry-${original.units[0].id}").performClick()
            compose.onNode(calendarDay(15)).performScrollTo().performClick();tag("confirm-expiry-date").performClick()
            assertEquals(original,runBlocking {repo.findById(original.id)})
            scroll("save-item-edit").assertIsDisplayed();shot("edit")
            scroll("save-item-edit").performClick();compose.waitUntil(5000) {vm.state.value.detail?.name=="早餐牛奶" && !vm.state.value.busy}
            val changed=runBlocking {repo.findById(original.id)}!!
            assertEquals(original.id,changed.id);assertEquals(original.createdAt,changed.createdAt)
            assertEquals(original.units.drop(1),changed.units.drop(1));assertEquals(LocalDate.now().withDayOfMonth(15).toString(),changed.units[0].expiryDate)
            scroll("adjust-unit-${original.units[1].id}").performClick()
            compose.waitUntil(5000) {exists("expiry-date-page")}
            compose.onNode(calendarDay(18)).performScrollTo().performClick();tag("confirm-expiry-date").performClick()
            scroll("save-item-edit").performClick();compose.waitUntil(5000) {vm.state.value.detail?.units?.get(1)?.expiryDate==LocalDate.now().withDayOfMonth(18).toString()}
            scroll("add-item-inventory").performClick();compose.waitUntil(5000) {exists("draft-screen")}
            scroll("draft-count").performTextReplacement("2")
            selectExpiryDay(compose,1,20)
            scroll("confirm-draft").performClick();compose.waitUntil(5000) {vm.state.value.detail?.quantity==5}
            val all=vm.state.value.detail!!
            scroll("delete-unit-${all.units.last().id}").performClick();tag("delete-confirmation").assertExists();shot("delete")
            tag("cancel-delete").performClick();assertEquals(5,runBlocking {repo.findById(original.id)}!!.quantity)
            for(unit in all.units) {
                scroll("delete-unit-${unit.id}").performClick();tag("confirm-delete").performClick()
                compose.waitUntil(5000) {!vm.state.value.busy && vm.state.value.pendingDeletion==null}
                tag("item-detail-sheet").assertExists()
            }
            assertEquals(0,vm.state.value.detail!!.quantity);scroll("units-empty");shot("zero");tag("units-empty").assertIsDisplayed()
            scroll("close-detail").performClick();compose.waitUntil(5000) {!exists("item-detail-sheet")}
            compose.waitUntil(5000) {vm.items.value.rows.first {it.id==original.id}.quantity==0}
            scroll("stored-item-${original.id}").assertTextContains("早餐牛奶",substring=true)
            tag("tab-history").performClick()
            compose.waitUntil(5000) {vm.history.value.rows.isNotEmpty()}
            val key=vm.history.value.rows.first().key
            tag("history-screen").performScrollToNode(hasTestTag("history-$key"));tag("history-$key").performClick()
            compose.waitUntil(5000) {vm.state.value.detail?.id==original.id}
            scroll("detail-name").assertTextContains("早餐牛奶");scroll("detail-quantity").assertTextContains("0",substring=true)
            assertEquals(0,nlu.calls);assertEquals(25,vm.items.value.rows.size)
            File(ctx.filesDir,"items-validation.json").writeText(JSONObject().put("isolatedDb",true).put("all25ItemsReachable",true)
                .put("renamePreservesIdentity",true).put("duplicateNameRejected",true).put("cancelDoesNotWrite",true)
                .put("expiryAdjustment",true).put("manualAddInventory",true).put("singleDeleteConfirmation",true)
                .put("zeroStockItemRetained",true).put("historyShowsCurrentItem",true).put("nluCalls",nlu.calls).toString(2))
        } finally {V2ValidationActivity.factory=null;inst.runOnMainSync {store.clear()};db.close()}
    }
}
