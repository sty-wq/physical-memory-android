package dev.local.physicalmemory

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.draft.*
import dev.local.physicalmemory.history.*
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.*
import dev.local.physicalmemory.ui.voice.*
import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

class UiUxRefactorTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=inst.targetContext
    private fun tag(s:String)=compose.onNodeWithTag(s)
    private fun exists(s:String)=compose.onAllNodesWithTag(s).fetchSemanticsNodes().isNotEmpty()
    private fun shell(command:String) { inst.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
    private fun shot(name:String) {
        compose.waitForIdle();inst.waitForIdleSync();Thread.sleep(300)
        inst.uiAutomation.takeScreenshot()?.let { bmp -> File(ctx.filesDir,"ui-$name.png").outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bmp.recycle() }
    }
    private fun scroll(name:String):SemanticsNodeInteraction {
        if(name=="hold-to-talk") return tag(name).assertIsDisplayed()
        val container=when {exists("item-detail-sheet")->"detail-list";exists("draft-screen")->"draft-screen";else->null}
        if(container!=null) tag(container).performScrollToNode(hasTestTag(name))
        return tag(name).performScrollTo()
    }
    private fun closeSheet() { if(exists("item-detail-sheet")) { scroll("close-detail").performClick();compose.waitUntil(5000) {!exists("item-detail-sheet")} } }
    private fun input(text:String) { closeSheet();scroll("command-input").performTextReplacement(text);scroll("parse-button").performClick() }
    private fun fixture(speech:SpeechInput?=null,block:(InventoryViewModel,RoomInventoryRepository,InMemoryHistoryStore,FakeNluEngine)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build();val repo=RoomInventoryRepository(db)
        val history=InMemoryHistoryStore();val store=ViewModelStore()
        val nlu=FakeNluEngine { text,_->
            when {
                text.contains("增加") -> NluResult.ProposeAddUnits("牛奶",3,"袋",null,null)
                text.contains("R8") -> NluResult.UpsertItemInfo("R8",LocationChange(LocationOp.SET,"防潮箱"))
                text.contains("不存在") -> NluResult.OpenItem("不存在")
                else -> NluResult.OpenItem("牛奶")
            }
        }
        val delayed=object:NluEngine by nlu {
            override suspend fun parse(text:String,currentDate:LocalDate):Result<NluResult> { delay(800);return nlu.parse(text,currentDate) }
        }
        lateinit var vm:InventoryViewModel
        inst.runOnMainSync { vm=InventoryViewModel(repo,delayed,speech,date={LocalDate.of(2026,9,2)},historyStore=history);store.put("ui",vm) }
        V2ValidationActivity.factory={vm}
        try {
            shell("am start -W -n dev.local.physicalmemory/.V2ValidationActivity")
            compose.waitUntil(20000) {exists("home-screen")}
            block(vm,repo,history,nlu)
        } finally {
            V2ValidationActivity.factory=null;inst.runOnMainSync {store.clear()};db.close()
        }
    }
    @Test fun navigationHistoryCurrentStateAndDeleteInPlace()=fixture { vm,repo,_,_->
        tag("history-screen").assertDoesNotExist();tag("detail-list").assertDoesNotExist();shot("home-idle")
        tag("tab-history").performClick();tag("history-empty").assertExists();tag("tab-home").performClick()
        input("R8放在防潮箱");compose.waitUntil(5000) {exists("draft-screen")}
        tag("home-screen").assertDoesNotExist();assertNull(runBlocking {repo.findByName("R8")})
        scroll("draft-item").performTextReplacement("R8")
        scroll("draft-location").performClick();shot("draft-keyboard")
        scroll("confirm-draft").assertIsDisplayed();shot("draft-keyboard-actions")
        tag("confirm-draft").performTouchInput {click()};compose.waitUntil(5000) {vm.state.value.detail?.name=="R8"}
        assertEquals(1,vm.history.value.rows.size);closeSheet()
        runBlocking {repo.confirm(DraftFactory(repo).create(NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱",null),"fixture"))}
        input("增加三袋牛奶");compose.waitUntil(5000) {exists("draft-screen") && vm.state.value.draft is AddUnitsDraft}
        selectExpiryDay(compose,1,5)
        selectExpiryDay(compose,2,8)
        shot("draft-editor");scroll("confirm-draft").performClick()
        compose.waitUntil(5000) {vm.state.value.detail?.quantity==6}
        val before=runBlocking {repo.findByName("牛奶")}!!
        scroll("detail-location").assertTextContains("冰箱",substring=true)
        scroll("detail-quantity").assertTextContains("6",substring=true)
        before.units.forEach { scroll("delete-unit-${it.id}").assertExists();scroll("unit-expiry-${it.id}").assertTextContains(it.expiryDate ?: "未记录",substring=true) }
        scroll("detail-name");shot("item-detail")
        val selected=before.units[3]
        scroll("delete-unit-${selected.id}").performClick();tag("delete-confirmation").assertExists();shot("delete-confirmation")
        tag("cancel-delete").performClick();assertEquals(before,runBlocking {repo.findByName("牛奶")})
        scroll("delete-unit-${selected.id}").performClick();tag("confirm-delete").performClick()
        compose.waitUntil(5000) {vm.state.value.detail?.quantity==5};tag("item-detail-sheet").assertExists()
        assertEquals(before.units.filterNot {it.id==selected.id},runBlocking {repo.findByName("牛奶")}!!.units)
        closeSheet();tag("tab-history").performClick();tag("history-screen").assertExists();shot("history")
        val added=vm.history.value.rows.single {it.summary.startsWith("增加")}
        tag("history-${added.key}").performClick();compose.waitUntil(5000) {vm.state.value.detail?.quantity==5}
        scroll("detail-quantity").assertTextContains("5",substring=true) // Current state, not the earlier six.
        while(vm.state.value.detail!!.quantity>0) {
            val current=vm.state.value.detail!!;val unit=current.units.first()
            scroll("delete-unit-${unit.id}").performClick();tag("confirm-delete").performClick()
            compose.waitUntil(5000) {vm.state.value.detail?.quantity==current.quantity-1}
            tag("item-detail-sheet").assertExists()
        }
        scroll("units-empty").assertExists();assertNotNull(runBlocking {repo.findById(before.id)})
        closeSheet();tag("tab-home").performClick();input("牛奶在哪")
        compose.waitUntil(5000) {exists("item-detail-sheet")};tag("draft-screen").assertDoesNotExist()
        closeSheet();input("不存在在哪");compose.waitUntil(5000) {!vm.state.value.busy}
        tag("item-detail-sheet").assertDoesNotExist();tag("operation-message").assertTextContains("不存在",substring=true)
        File(ctx.filesDir,"ui-navigation-result.json").writeText("{\"isolatedDb\":true,\"fakeNlu\":true,\"homeSeparated\":true,\"draftRoutes\":true,\"historyCurrentState\":true,\"singleDelete\":true,\"zeroStockItemRetained\":true}")
    }
    private class CountingSpeech: SessionSpeechInput(SpeechEngine.QWEN3_ASR,SpeechAvailability(true,"test","FAKE")) {
        var starts=0;var decodes=0;var cancels=0
        override fun startListening(sessionId:String) {if(begin(sessionId)) {starts++;listening(sessionId)}}
        override fun stopListening() {activeSession?.let {decodes++;finish(it,"R8放在防潮箱")}}
        override fun cancel() {if(activeSession!=null)cancels++;invalidate()}
        override fun release() {cancel();markReleased()}
    }
    @Test fun pointerHoldCancelRestoreShortMultitouchAndProcessing() {
        val speech=CountingSpeech()
        fixture(speech) { vm,_,_,nlu ->
            compose.waitUntil(5000) {vm.hold.state.value.ready}
            tag("command-input").performClick();tag("hold-to-talk").assertIsDisplayed();shot("home-bottom-keyboard")
            shell("input keyevent KEYCODE_BACK");compose.waitForIdle()
            val home=tag("home-screen").fetchSemanticsNode().boundsInRoot
            val button=tag("hold-to-talk").fetchSemanticsNode().boundsInRoot
            assertTrue("voice button stays immediately above bottom navigation",home.bottom-button.bottom < 24*ctx.resources.displayMetrics.density)
            assertTrue("voice button is in lower part of Home",button.center.y > home.top+home.height*0.75f)
            fun down() {scroll("hold-to-talk").performTouchInput {down(center)};compose.waitUntil(3000) {vm.hold.state.value.phase==HoldPhase.Recording}}
            fun up() {tag("hold-to-talk").performTouchInput {up(0)}}
            fun move(dy:Float) {tag("hold-to-talk").performTouchInput {moveTo(Offset(center.x,center.y+dy))}}
            val threshold=ctx.resources.displayMetrics.density*130
            down();Thread.sleep(500);shot("home-recording");move(-threshold)
            compose.waitUntil(3000) {vm.hold.state.value.phase==HoldPhase.CancelArmed};shot("home-cancel-armed");up()
            assertEquals(0,speech.decodes);assertEquals(0,nlu.calls);assertTrue(vm.history.value.rows.isEmpty());tag("home-screen").assertExists()
            down();move(-threshold);move(0f);Thread.sleep(500);up()
            assertEquals(1,speech.decodes);assertFalse(vm.hold.down())
            compose.waitUntil(5000) {exists("draft-screen")};tag("draft-back").performClick()
            compose.waitUntil(5000) {exists("home-screen")}
            val completed=speech.decodes
            scroll("hold-to-talk").performTouchInput {down(center);advanceEventTime(30);up(0)}
            assertEquals(completed,speech.decodes)
            down();tag("hold-to-talk").performTouchInput {down(1,center+Offset(15f,0f));up(1);up(0)}
            assertEquals(completed,speech.decodes)
            down();tag("hold-to-talk").performTouchInput {cancel()};assertEquals(completed,speech.decodes)
            down();Thread.sleep(500);up();compose.waitUntil(5000) {exists("draft-screen")}
            assertEquals(completed+1,speech.decodes);assertEquals(2,nlu.calls);assertTrue(vm.history.value.rows.isEmpty())
            File(ctx.filesDir,"ui-gesture-result.json").writeText("{\"input\":\"synthetic_test_adapter\",\"normalAndRestoreDecodeCount\":2,\"cancelShortMultitouchSystemCancelDecodes\":0,\"unconfirmedHistoryCount\":0}")
        }
    }

    @Test fun calendarConfirmationCancellationAndClearStayInDraft()=fixture { vm,repo,history,_->
        input("增加三袋牛奶");compose.waitUntil(5000) {vm.state.value.draft is AddUnitsDraft}
        scroll("draft-expiry-1").performClick();tag("expiry-date-page").assertIsDisplayed()
        shot("calendar-initial")
        tag("confirm-expiry-date").assertIsNotEnabled()
        compose.onNode(calendarDay(LocalDate.now().lengthOfMonth())).performScrollTo().assertIsDisplayed().performClick()
        tag("selected-expiry-date").assertTextEquals(LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth()).toString())
        compose.onNode(calendarDay(5)).performScrollTo().performClick()
        assertEquals("",vm.state.value.draft!!.data.units[0].expiryDate)
        shot("calendar-date-selection");tag("cancel-expiry-date").performClick()
        assertEquals("",vm.state.value.draft!!.data.units[0].expiryDate)
        selectExpiryDay(compose,1,8)
        val expected=LocalDate.now().withDayOfMonth(8).toString()
        assertEquals(expected,vm.state.value.draft!!.data.units[0].expiryDate)
        assertTrue(vm.state.value.draft!!.data.units.drop(1).all {it.expiryDate.isBlank()})
        scroll("draft-expiry-1").performClick();tag("selected-expiry-date").assertTextEquals(expected)
        shell("input keyevent KEYCODE_BACK");compose.waitUntil(5000) {!exists("expiry-date-page")}
        assertEquals(expected,vm.state.value.draft!!.data.units[0].expiryDate)
        scroll("draft-expiry-1").performClick();tag("clear-expiry-date").performClick()
        assertEquals("",vm.state.value.draft!!.data.units[0].expiryDate)
        assertNull(runBlocking {repo.findByName("牛奶")});assertTrue(vm.history.value.rows.isEmpty())
        shot("calendar-draft-fields")
    }
}
