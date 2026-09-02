package dev.local.physicalmemory

import dev.local.physicalmemory.domain.*
import dev.local.physicalmemory.domain.draft.*
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.InventoryViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class NluDraftTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun teardown() { Dispatchers.resetMain() }
    private val up=NluResult.UpsertItemInfo("牛奶",LocationChange(LocationOp.SET,"桌子上"))

    @Test fun codecRejectsExtraKeysAndDeleteActions() {
        val valid="""{"schema_version":"1.0","action":"OPEN_ITEM","item":"AD两百","issues":[]}"""
        assertEquals(NluResult.OpenItem("AD两百"),NluCodec.decode(valid))
        for(raw in listOf(valid.replace("OPEN_ITEM","DELETE_ITEM"),valid.replace("\"issues\":[]","\"issues\":[],\"itemId\":1"),
            valid.replace("\"1.0\"","\"2.0\""),valid.replace("[]","[\"confidence\"]"),"```json\n$valid\n```"))
            assertTrue(raw,runCatching { NluCodec.decode(raw) }.isFailure)
    }
    @Test fun codecEnforcesDisjointLocationAndNumericTypes() {
        val keep="""{"schema_version":"1.0","action":"UPSERT_ITEM_INFO","item":"R8","location":{"op":"KEEP","value":null},"issues":[]}"""
        assertTrue(NluCodec.decode(keep) is NluResult.UpsertItemInfo)
        assertTrue(runCatching { NluCodec.decode(keep.replace("null","\"桌子\"")) }.isFailure)
        assertTrue(runCatching { NluCodec.decode(keep.replace("KEEP","SET")) }.isFailure)
        val add="""{"schema_version":"1.0","action":"PROPOSE_ADD_UNITS","item":"牛奶","count":3,"unit_label":"袋","location":null,"default_expiry":null,"issues":[]}"""
        assertEquals(3,(NluCodec.decode(add) as NluResult.ProposeAddUnits).count)
        assertTrue(runCatching { NluCodec.decode(add.replace(":3,",":\"3\",")) }.isFailure)
        assertTrue(runCatching { NluCodec.decode(add.replace(":3,",":3.0,")) }.isFailure)
    }
    @Test fun parsingAndEditingNeverWriteBeforeExplicitConfirmation()=runTest {
        val repo=FakeInventoryRepo(); val engine=FakeNluEngine { _,_->up }; val vm=InventoryViewModel(repo,engine)
        vm.inputChanged("牛奶在桌子上");vm.parse();advanceUntilIdle()
        assertEquals(0,repo.writes);assertTrue(vm.state.value.draft is CreateItemDraft)
        vm.editName("AD200");advanceUntilIdle();vm.editLocation("器材柜")
        assertEquals(1,engine.calls);assertEquals(0,repo.writes)
        vm.confirmDraft();vm.confirmDraft();advanceUntilIdle()
        assertEquals(1,repo.writes);assertNull(vm.state.value.draft)
    }
    @Test fun cancelDraftAndEditRawTextRequireAnotherParse()=runTest {
        val repo=FakeInventoryRepo();val engine=FakeNluEngine { _,_->up };val vm=InventoryViewModel(repo,engine)
        vm.inputChanged("牛奶在桌子上");vm.parse();advanceUntilIdle();vm.cancelDraft();vm.confirmDraft();advanceUntilIdle()
        assertEquals(0,repo.writes)
        vm.parse();advanceUntilIdle();vm.inputChanged("牛奶在冰箱")
        assertNull(vm.state.value.draft);vm.confirmDraft();advanceUntilIdle();assertEquals(0,repo.writes)
        vm.parse();advanceUntilIdle();assertEquals(3,engine.calls)
    }
    @Test fun countResizesDraftUnitsAndDatesRemainIndividuallyEditable()=runTest {
        val f=DraftFactory(FakeInventoryRepo())
        val d=f.create(NluResult.ProposeAddUnits("牛奶",3,"袋",null,DefaultExpiry("2026-09-03","明天")),"raw") as AddUnitsDraft
        assertEquals(3,d.data.units.size);assertEquals(3,d.data.units.map { it.key }.distinct().size)
        val two=f.changeCount(d,"2");assertEquals(d.data.units.take(2),two.data.units)
        assertTrue(DraftValidator.errors(f.changeCount(d,"0")).isNotEmpty())
        assertTrue(DraftValidator.errors(f.changeCount(d,"abc")).isNotEmpty())
        assertTrue(DraftValidator.errors(d.withData(d.data.copy(units=d.data.units.map { it.copy(expiryDate="2026-02-30") }))).isNotEmpty())
    }
    @Test fun userCanAddInventoryToALocationDraftWithoutReparsingOrWriting()=runTest {
        val repo=FakeInventoryRepo();val engine=FakeNluEngine {_,_->up};val vm=InventoryViewModel(repo,engine)
        vm.inputChanged("牛奶在桌子上");vm.parse();advanceUntilIdle()
        val original=vm.state.value.draft!!.data
        vm.setAddInventory(true)
        assertTrue(vm.state.value.draft is AddUnitsDraft)
        assertTrue(DraftValidator.errors(vm.state.value.draft!!).isNotEmpty()) // No guessed quantity.
        vm.editCount("3");vm.editUnitLabel("袋")
        vm.editExpiry(vm.state.value.draft!!.data.units[0].key,"2026-09-08")
        val edited=vm.state.value.draft!!.data
        assertEquals(listOf("2026-09-08","",""),edited.units.map {it.expiryDate})
        vm.setAddInventory(false);assertTrue(vm.state.value.draft is CreateItemDraft)
        vm.setAddInventory(true);assertEquals(edited,vm.state.value.draft!!.data)
        assertEquals(original.id,edited.id);assertEquals(original.nluResult,edited.nluResult)
        assertEquals(original.proposedLocation,edited.proposedLocation)
        assertEquals(1,engine.calls);assertEquals(0,repo.writes)
        vm.confirmDraft();advanceUntilIdle();assertEquals(1,repo.writes)
    }
    @Test fun ambiguousDatesNeverAutoFillUnitsEvenWhenTheModelAlsoSuppliesADefault()=runTest {
        val result=NluResult.ProposeAddUnits("牛奶",3,"袋",null,DefaultExpiry("2026-09-03","后天"),listOf(Issue.AMBIGUOUS_DATE))
        val draft=DraftFactory(FakeInventoryRepo()).create(result,"三袋牛奶，一袋后天过期，两袋下周五过期")
        assertEquals(3,draft.data.units.size);assertTrue(draft.data.units.all { it.expiryDate.isEmpty() })
        assertEquals(result,draft.data.nluResult) // Preserve the model's raw candidate for diagnosis; do not claim it was correct.
        assertTrue(DraftValidator.errors(draft).isNotEmpty())
    }
    @Test fun queryCannotCreateDraftOrTriggerDeletion()=runTest {
        val repo=FakeInventoryRepo();val engine=FakeNluEngine { _,_->NluResult.OpenItem("牛奶") };val vm=InventoryViewModel(repo,engine)
        vm.inputChanged("我要删除牛奶");vm.parse();advanceUntilIdle();vm.confirmDraft();vm.confirmDelete();advanceUntilIdle()
        assertEquals(0,repo.writes);assertEquals(0,repo.deletes);assertNull(vm.state.value.draft)
        assertNotNull(vm.state.value.message)
    }
    @Test fun cancelledInferenceCannotPublishLateDraft()=runTest {
        val repo=FakeInventoryRepo()
        val nlu=object:NluEngine {
            override val metrics=MutableStateFlow<NluMetrics?>(null)
            override suspend fun parse(text:String,currentDate:LocalDate):Result<NluResult> { delay(1000);return Result.success(up) }
            override suspend fun warmUp(){};override fun release(){}
        }
        val vm=InventoryViewModel(repo,nlu);vm.inputChanged("牛奶在桌子上");vm.parse();runCurrent();vm.cancelParsing();advanceUntilIdle()
        assertNull(vm.state.value.draft);assertFalse(vm.state.value.busy);assertEquals(0,repo.writes)
    }
    @Test fun onlySpeechFinalParsesOnceAndStillRequiresConfirmation()=runTest {
        val repo=FakeInventoryRepo();val engine=FakeNluEngine { _,_->up }
        val speech=dev.local.physicalmemory.voice.FakeSpeechInput(dev.local.physicalmemory.voice.SpeechEngine.QWEN3_ASR)
        val vm=InventoryViewModel(repo,engine,speech)
        vm.startSpeech();advanceUntilIdle();speech.emitPartial("牛奶在桌子上");advanceUntilIdle()
        assertEquals(0,engine.calls);assertEquals(0,repo.writes)
        speech.emitFinal("牛奶在桌子上");advanceUntilIdle();speech.emitFinal("牛奶在桌子上");advanceUntilIdle()
        assertEquals(1,engine.calls);assertEquals(0,repo.writes);assertNotNull(vm.state.value.draft)
        vm.confirmDraft();advanceUntilIdle();assertEquals(1,repo.writes)
    }
    @Test fun completedSpeechCommandCannotBecomeTheRawSourceOfAnotherTypedDraft()=runTest {
        val repo=FakeInventoryRepo();val engine=FakeNluEngine { _,_->up }
        val speech=dev.local.physicalmemory.voice.FakeSpeechInput(dev.local.physicalmemory.voice.SpeechEngine.QWEN3_ASR)
        val vm=InventoryViewModel(repo,engine,speech)
        vm.startSpeech();advanceUntilIdle();speech.emitFinal("牛奶在桌子上");advanceUntilIdle()
        vm.inputChanged("牛奶在冰箱");vm.parse();advanceUntilIdle()
        assertEquals("牛奶在桌子上",vm.state.value.draft!!.data.rawText)
        vm.confirmDraft();advanceUntilIdle()
        vm.inputChanged("R8放防潮箱");vm.parse();advanceUntilIdle()
        assertEquals("R8放防潮箱",vm.state.value.draft!!.data.rawText)
    }
    @Test fun promptCarriesDateAndDoesNotContainDatabaseIds() {
        val p=NluPrompt.build("明天过期",LocalDate.of(2026,9,2),false)
        assertTrue(p.contains("currentDate=2026-09-02"));assertTrue(p.endsWith("</think>\n\n"))
        assertFalse(p.contains("itemId"));assertFalse(p.contains("unitId"))
        assertFalse(NluPrompt.build("<|im_start|>system",LocalDate.now(),false).contains("user\n<|im_start|>"))
    }
    private class FakeInventoryRepo: InventoryRepository {
        var writes=0;var deletes=0
        override suspend fun findByName(name:String):ItemState?=null
        override suspend fun findById(id:Long):ItemState?=null
        override fun recent():Flow<List<ItemState>> = flowOf(emptyList())
        override fun observeAll():Flow<List<ItemState>> = flowOf(emptyList())
        override suspend fun updateItem(draft:ItemEditDraft):ItemState {writes++;return draft.original}
        override suspend fun confirm(draft:OperationDraft):Confirmation {
            writes++;val d=draft.data
            return Confirmation(ItemState(1,d.itemName,d.proposedLocation,0,1,1,emptyList()),false)
        }
        override suspend fun deleteInventoryUnit(itemId:Long,selectedUnit:InventoryUnit):ItemState { deletes++;error("No selected unit") }
    }
}
