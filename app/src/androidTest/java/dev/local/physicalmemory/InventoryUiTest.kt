package dev.local.physicalmemory

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.draft.*
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

class InventoryUiTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun tag(name:String)=compose.onNodeWithTag(name)
    private fun scrollTag(name:String): SemanticsNodeInteraction {
        val container=when {
            compose.onAllNodesWithTag("item-detail-sheet").fetchSemanticsNodes().isNotEmpty()->"detail-list"
            compose.onAllNodesWithTag("draft-screen").fetchSemanticsNodes().isNotEmpty()->"draft-screen"
            else->null
        }
        if(container!=null) compose.onNodeWithTag(container).performScrollToNode(hasTestTag(name))
        return tag(name).performScrollTo()
    }
    private fun screenshot(name:String) {
        val inst=InstrumentationRegistry.getInstrumentation()
        compose.waitForIdle(); inst.waitForIdleSync(); Thread.sleep(300)
        inst.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(inst.targetContext.filesDir,"v2-ui-$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
    private fun textInput(text:String) {
        if(compose.onAllNodesWithTag("item-detail-sheet").fetchSemanticsNodes().isNotEmpty()) {
            scrollTag("close-detail").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithTag("item-detail-sheet").fetchSemanticsNodes().isEmpty()}
        }
        scrollTag("command-input").performTextReplacement(text);scrollTag("parse-button").performClick() }
    private fun waitDraft() { compose.waitUntil(90000) { compose.onAllNodesWithTag("draft-item").fetchSemanticsNodes().isNotEmpty() } }
    @Test fun editableConfirmationAndSingleDeletionAtoD() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val ctx=instrumentation.targetContext
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build();val repo=RoomInventoryRepository(db)
        val real=InstrumentationRegistry.getArguments().getString("realNlu")=="true"
        val nlu:NluEngine=if(real) Qwen3NluEngine({LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}"))},
            ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use { it.readText() }) else FakeNluEngine { t,_->
                when(t) {
                    "R8放在防潮箱" -> NluResult.UpsertItemInfo("R8",LocationChange(LocationOp.SET,"防潮箱"))
                    "牛奶在桌子上" -> NluResult.UpsertItemInfo("牛奶",LocationChange(LocationOp.SET,"桌子上"))
                    "增加三袋牛奶" -> NluResult.ProposeAddUnits("牛奶",3,"袋",null,null)
                    else -> NluResult.OpenItem("牛奶")
                }
            }
        val store=androidx.lifecycle.ViewModelStore()
        lateinit var vm:InventoryViewModel
        instrumentation.runOnMainSync { vm=InventoryViewModel(repo,nlu,date={LocalDate.of(2026,9,2)}); store.put("ui",vm) }
        V2ValidationActivity.factory={vm}
        try {
            // Xiaomi blocks background ActivityScenario launches; the authorized adb-shell launch is foreground-capable.
            instrumentation.uiAutomation.executeShellCommand("am start -W -n dev.local.physicalmemory/.V2ValidationActivity").use { pfd ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).readBytes()
            }
            compose.waitUntil(20000) { compose.onAllNodesWithTag("command-input").fetchSemanticsNodes().isNotEmpty() }
            textInput("R8放在防潮箱");waitDraft()
            tag("draft-item").assertTextContains("R8");tag("draft-location").assertTextContains("防潮箱")
            assertNull(runBlocking { repo.findByName("R8") }); screenshot("${if(real) "real" else "fake"}-A")
            scrollTag("confirm-draft").performClick()
            compose.waitUntil(5000) { vm.state.value.detail?.name=="R8" }
            assertEquals("防潮箱",runBlocking { repo.findByName("R8") }!!.location)
            runBlocking { val f=DraftFactory(repo);repo.confirm(f.create(NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱",null),"fixture")) }
            val before=runBlocking { repo.findByName("牛奶") }!!
            textInput("牛奶在桌子上");waitDraft()
            scrollTag("current-location").assertTextContains("冰箱",substring=true)
            scrollTag("location-change").assertTextContains("冰箱 → 桌子上")
            assertEquals(before,runBlocking { repo.findByName("牛奶") }); screenshot("${if(real) "real" else "fake"}-B")
            scrollTag("confirm-draft").performClick()
            compose.waitUntil(5000) { vm.state.value.detail?.name=="牛奶" }
            assertEquals(before.units,runBlocking { repo.findByName("牛奶") }!!.units)
            textInput("增加三袋牛奶");waitDraft()
            scrollTag("draft-count").assertTextContains("3")
            selectExpiryDay(compose,1,5)
            selectExpiryDay(compose,2,8); screenshot("${if(real) "real" else "fake"}-C")
            scrollTag("confirm-draft").performClick()
            compose.waitUntil(5000) { vm.state.value.detail?.quantity==6 }
            textInput("牛奶在哪")
            compose.waitUntil(90000) { vm.state.value.detail?.quantity==6 && !vm.state.value.busy }
            scrollTag("detail-location").assertTextContains("桌子上",substring=true)
            tag("detail-quantity").assertTextContains("6",substring=true)
            val all=runBlocking { repo.findByName("牛奶") }!!
            assertEquals(listOf(LocalDate.now().withDayOfMonth(5).toString(),LocalDate.now().withDayOfMonth(8).toString(),null),all.units.takeLast(3).map { it.expiryDate })
            val selected=all.units[3]
            scrollTag("delete-unit-${selected.id}").performClick()
            tag("confirm-delete").assertExists(); screenshot("${if(real) "real" else "fake"}-delete")
            assertEquals(6,runBlocking { repo.findByName("牛奶") }!!.quantity)
            tag("confirm-delete").performClick()
            compose.waitUntil(5000) { vm.state.value.detail?.quantity==5 }
            assertEquals(all.units.filterNot { it.id==selected.id },runBlocking { repo.findByName("牛奶") }!!.units)
            File(ctx.filesDir,"v2-ui-${if(real) "real" else "fake"}.json").writeText("{\"A\":true,\"B\":true,\"C\":true,\"D\":true,\"singleDelete\":true,\"isolatedDb\":true,\"realNlu\":$real}")
        } finally { V2ValidationActivity.factory=null;instrumentation.runOnMainSync { store.clear() };db.close() }
    }
}
