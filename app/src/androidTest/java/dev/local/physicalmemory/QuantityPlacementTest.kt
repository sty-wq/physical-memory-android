package dev.local.physicalmemory

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.draft.*
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** Real Q8_0 NLU and production Compose flow; exclusively an isolated in-memory inventory. */
class QuantityPlacementTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private fun tag(s:String)=compose.onNodeWithTag(s)
    private fun scroll(s:String)=tag("draft-screen").performScrollToNode(hasTestTag(s)).let {tag(s)}
    private fun shot(name:String) {
        compose.waitForIdle()
        inst.uiAutomation.takeScreenshot()?.let {b->File(inst.targetContext.filesDir,"quantity-$name.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    }
    @Test fun quantifiedPlacementAndEditableInventory() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("quantityRealNlu")=="true")
        val ctx=inst.targetContext
        val date=LocalDate.now()
        val records=File(ctx.filesDir,"quantity-nlu.jsonl").apply {writeText("")}
        val nlu=Qwen3NluEngine({LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}"))},
            ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use {it.readText()},observer={text,raw,m,error->
                records.appendText(JSONObject().put("text",text).put("raw",raw).put("promptVersion",NluPrompt.VERSION)
                    .put("error",error ?: JSONObject.NULL).put("totalNluMs",m.totalNluMs).toString()+"\n")
            })
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build();val repo=RoomInventoryRepository(db)
        // Match the reported screen: an existing milk Item with zero units.
        runBlocking {repo.confirm(DraftFactory(repo).create(NluResult.UpsertItemInfo("牛奶",LocationChange(LocationOp.SET,"冰箱里")),"fixture"))}
        val store=ViewModelStore();lateinit var vm:InventoryViewModel
        inst.runOnMainSync {vm=InventoryViewModel(repo,nlu,date={date});store.put("quantity",vm)}
        V2ValidationActivity.factory={vm}
        fun parse(text:String) {
            inst.runOnMainSync {vm.inputChanged(text);vm.parse()}
            compose.waitUntil(90000) {!vm.state.value.busy && vm.state.value.draft!=null}
        }
        try {
            inst.uiAutomation.executeShellCommand("am start -W -n ${ctx.packageName}/.V2ValidationActivity").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            compose.waitUntil(15000) {compose.onAllNodesWithTag("home-screen").fetchSemanticsNodes().isNotEmpty()}
            parse("冰箱里放了三袋牛奶。")
            assertTrue("Quantity-bearing placement must create AddUnitsDraft; actual=${vm.state.value.draft}",vm.state.value.draft is AddUnitsDraft)
            val candidate=vm.state.value.draft!!.data
            assertEquals("牛奶",candidate.itemName);assertEquals("冰箱里",candidate.proposedLocation)
            assertEquals("3",candidate.countText);assertEquals("袋",candidate.unitLabel)
            assertEquals(3,candidate.units.size);assertTrue(candidate.units.all {it.expiryDate.isEmpty()})
            scroll("draft-count").assertTextContains("3");shot("count")
            scroll("draft-count").performTextReplacement("4")
            scroll("draft-expiry-4").assertIsDisplayed()
            scroll("draft-count").performTextReplacement("3")
            selectExpiryDay(compose,1,5);selectExpiryDay(compose,2,8)
            scroll("draft-expiry-3").assertIsDisplayed();shot("dates")
            assertEquals(0,runBlocking {repo.findByName("牛奶")}!!.quantity)
            scroll("confirm-draft").performClick()
            compose.waitUntil(5000) {vm.state.value.detail?.quantity==3}
            val saved=runBlocking {repo.findByName("牛奶")}!!
            assertEquals(listOf(date.withDayOfMonth(5).toString(),date.withDayOfMonth(8).toString(),null),saved.units.map {it.expiryDate})
            inst.runOnMainSync {vm.dismissDetail()}
            parse("牛奶在桌子上")
            assertTrue(vm.state.value.draft is UpdateItemDraft)
            scroll("draft-add-inventory").performClick()
            scroll("draft-count").performTextReplacement("2")
            selectExpiryDay(compose,1,9)
            val edited=vm.state.value.draft!!.data.units
            scroll("draft-add-inventory").performClick()
            assertTrue(vm.state.value.draft is UpdateItemDraft)
            tag("draft-count").assertDoesNotExist();tag("draft-expiry-1").assertDoesNotExist()
            scroll("draft-add-inventory").performClick()
            assertEquals(edited,vm.state.value.draft!!.data.units)
            scroll("draft-count").assertTextContains("2");shot("manual-inventory")
            scroll("draft-add-inventory").performClick()
            scroll("confirm-draft").performClick()
            compose.waitUntil(5000) {vm.state.value.detail?.location=="桌子上"}
            assertEquals(saved.units,runBlocking {repo.findByName("牛奶")}!!.units)
            // Exercise nearby phrases and prevent model numbers, moves and queries becoming new stock.
            val cases=listOf(
                "冰箱里放了三袋牛奶" to NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱里",null),
                "我把三袋牛奶放进冰箱里" to NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱里",null),
                "冰箱里放了三袋牛奶，明天过期" to NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱里",DefaultExpiry(date.plusDays(1).toString(),"明天")),
                "柜子里放了两瓶果汁" to NluResult.ProposeAddUnits("果汁",2,"瓶","柜子里",null),
                "R8放在防潮箱" to NluResult.UpsertItemInfo("R8",LocationChange(LocationOp.SET,"防潮箱")),
                "AD200放在器材柜" to NluResult.UpsertItemInfo("AD200",LocationChange(LocationOp.SET,"器材柜")),
                "把三袋牛奶从冰箱移到桌子上" to NluResult.UpsertItemInfo("牛奶",LocationChange(LocationOp.SET,"桌子上")),
                "冰箱里有几袋牛奶" to NluResult.OpenItem("牛奶"),
                "增加三袋牛奶" to NluResult.ProposeAddUnits("牛奶",3,"袋",null,null)
            )
            val mismatches=cases.mapNotNull {(text,expected)->
                val actual=runBlocking {nlu.parse(text,date).getOrThrow()}
                if(actual==expected) null else "$text expected=$expected actual=$actual"
            }
            File(ctx.filesDir,"quantity-validation.json").writeText(JSONObject().put("exactPhraseAndUiPassed",true)
                .put("manualInventoryTogglePassed",true).put("noWritesBeforeConfirmation",true).put("datesPersistedIndependently",true)
                .put("locationOnlyPreservesUnits",true).put("isolatedDb",true).put("mismatches",mismatches.joinToString("\n")).toString(2))
            assertTrue(mismatches.joinToString("\n"),mismatches.isEmpty())
        } finally {V2ValidationActivity.factory=null;inst.runOnMainSync {store.clear()};db.close()}
    }
}
