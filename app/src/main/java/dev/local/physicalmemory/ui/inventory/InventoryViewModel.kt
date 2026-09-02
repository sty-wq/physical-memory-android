package dev.local.physicalmemory.ui.inventory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.local.physicalmemory.domain.*
import dev.local.physicalmemory.domain.draft.*
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.util.UUID
import dev.local.physicalmemory.history.*
import dev.local.physicalmemory.ui.voice.*

data class PipelineTiming(val speechEnd: Long?, val asrFinal: Long?, val nluStart: Long, val nluFinal: Long,
    val draftReady: Long, val boundary: String?) {
    val asrLatency get() = speechEnd?.let { asrFinal?.minus(it) }
    val nluLatency get() = nluFinal-nluStart
    val draftLatency get() = draftReady-nluFinal
    val speechEndToDraftReady get() = speechEnd?.let { draftReady-it }
}
data class PendingDeletion(val itemName: String, val unit: InventoryUnit)
data class InventoryUiState(val input: String = "", val originalRaw: String = "", val busy: Boolean = false,
    val resolvingName: Boolean = false, val parsing: Boolean = false, val draft: OperationDraft? = null, val detail: ItemState? = null,
    val message: String? = null, val pendingDeletion: PendingDeletion? = null,
    val itemEdit: ItemEditDraft? = null, val editExpiryId: Long? = null,
    val editorOpen: Boolean = false, val nluMetrics: NluMetrics? = null, val pipeline: PipelineTiming? = null, val speech: SpeechUiState = SpeechUiState())

class InventoryViewModel(private val repository: InventoryRepository, private val nlu: NluEngine,
    private val speechInput: SpeechInput? = null, private val date: () -> LocalDate = LocalDate::now,
    private val clock: () -> Long = { System.nanoTime()/1_000_000 },
    private val pipelineObserver: (PipelineTiming) -> Unit = {},
    private val historyStore: HistoryStore = InMemoryHistoryStore(),
    private val wallClock: () -> Long = System::currentTimeMillis) : ViewModel() {
    private val factory = DraftFactory(repository)
    private val mutable = MutableStateFlow(InventoryUiState())
    val state = mutable.asStateFlow()
    private var parseJob: Job? = null
    private var nameJob: Job? = null
    private var generation = 0L
    private var nameGeneration = 0L
    private var session: String? = null
    private var consumed: String? = null
    val hold = HoldToTalkController(::startSpeech,::stopSpeech,::cancelCapture,clock)
    private val mutableHistory = MutableStateFlow(HistoryUiState())
    val history = mutableHistory.asStateFlow()
    private var preparation: Job? = null
    private val mutableItems=MutableStateFlow(ItemListUiState())
    val items=mutableItems.asStateFlow()
    private var itemsJob: Job? = null

    fun loadItems() {
        itemsJob?.cancel()
        mutableItems.update {it.copy(loading=true,error=null)}
        itemsJob=viewModelScope.launch {
            repository.observeAll().catch {mutableItems.update {it.copy(loading=false,error="暂时无法读取物品，请重试")}}
                .collect {rows->
                    mutableItems.value=ItemListUiState(rows,loading=false)
                    mutable.update {s->s.copy(detail=s.detail?.let {shown->rows.firstOrNull {it.id==shown.id}})}
                }
        }
    }

    fun prepareSpeech() {
        if(preparation?.isActive==true) return
        val input=speechInput
        if(input==null) { hold.prepared(false,"语音暂不可用，请输入文字");return }
        hold.preparing()
        preparation=viewModelScope.launch {
            try { input.warmUp(); hold.prepared(true) }
            catch(e: CancellationException) { throw e }
            catch(_: Throwable) { hold.prepared(false,"语音准备失败，请重试或输入文字") }
        }
    }
    private suspend fun recordHistory(key: String,item: ItemState,summary: String) {
        try { historyStore.append(HistoryRecord(key,item.id,item.name,summary,wallClock())) }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { mutableHistory.update { it.copy(error="操作已完成，但本次历史未保存") } }
    }


    init {
        loadItems()
        prepareSpeech()
        viewModelScope.launch { historyStore.observe().catch { mutableHistory.update { it.copy(error="暂时无法读取历史") } }
            .collect { rows -> mutableHistory.update { it.copy(rows=rows) } } }
        viewModelScope.launch { nlu.metrics.collect { m -> mutable.update { it.copy(nluMetrics=m) } } }
        speechInput?.let { input ->
            viewModelScope.launch { input.availability.collect { a -> mutable.update { it.copy(speech=it.speech.copy(
                availability=a)) } } }
            viewModelScope.launch { input.metrics.collect { m -> mutable.update { it.copy(speech=it.speech.copy(speechMetrics=m)) } } }
            viewModelScope.launch { input.state.collect { s ->
                if(s.sessionId != null && s.sessionId != session) return@collect
                mutable.update { it.copy(speech=it.speech.copy(speechState=s)) }
                when(s) {
                    is SpeechRecognitionState.Final -> if(consumed != s.sessionId) {
                        consumed=s.sessionId; session=null
                        mutable.update { it.copy(input=s.text, originalRaw=s.text) }
                        parse(input.metrics.value)
                    }
                    is SpeechRecognitionState.Listening -> hold.listening()
                    is SpeechRecognitionState.Recognizing, is SpeechRecognitionState.Finalizing -> hold.processing()
                    is SpeechRecognitionState.Error -> {
                        session=null;hold.complete(s.message)
                        mutable.update { it.copy(message=s.message) }
                    }
                    else -> Unit
                }
            } }
        }
    }
    fun inputChanged(text: String) {
        if(state.value.busy) return
        cancelSpeech()
        nameJob?.cancel(); nameGeneration++
        mutable.update { it.copy(input=text.take(512), originalRaw=if(text.isBlank()) "" else it.originalRaw,
            draft=null, detail=null, resolvingName=false, message=null) }
    }
    fun parse(asr: AsrMetrics? = null) {
        val snapshot = state.value
        if(snapshot.busy || snapshot.input.isBlank()) return
        val id = ++generation
        hold.processing()
        nameJob?.cancel(); nameGeneration++
        mutable.update { it.copy(busy=true,parsing=true,draft=null,detail=null,message=null,resolvingName=false,pendingDeletion=null) }
        parseJob = viewModelScope.launch {
            try {
                val start=clock()
                val result=nlu.parse(snapshot.input,date()).getOrThrow()
                val end=clock()
                val draft = when(result) {
                    is NluResult.UpsertItemInfo, is NluResult.ProposeAddUnits -> factory.create(result,
                        snapshot.originalRaw.ifBlank { snapshot.input },snapshot.input)
                    else -> null
                }
                val detail = if(result is NluResult.OpenItem && !result.item.isNullOrBlank()) repository.findByName(result.item) else null
                val message = when(result) {
                    is NluResult.Unknown -> "这句话没有可处理的物品操作。可以记录位置、增加库存，或查看物品。"
                    is NluResult.OpenItem -> if(result.item.isNullOrBlank()) "没有识别到物品名称，请修改文字后重新解析" else if(detail==null)
                        "还没有“${result.item}”的记录。请核对名称；当前按原名查找。" else null
                    else -> null
                }
                val timing=PipelineTiming(asr?.recordingStoppedAt ?: asr?.speechEndedAt,asr?.finalResultAt,start,end,clock(),
                    if(asr?.recordingStoppedAt != null) "manual_stop" else asr?.speechBoundarySource)
                if(id==generation) {
                    if(detail!=null) recordHistory("query-${UUID.randomUUID()}",detail,"查询 ${detail.name}")
                    mutable.update { it.copy(draft=draft,editorOpen=draft!=null,detail=detail,message=message,pipeline=timing,
                        originalRaw=if(draft==null) "" else it.originalRaw) }
                    runCatching { pipelineObserver(timing) }
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { if(id==generation) mutable.update { it.copy(message="解析未完成：${e.message ?: "请重试"}") } }
            finally { if(id==generation) { mutable.update { it.copy(busy=false,parsing=false) };hold.complete() } }
        }
    }
    fun cancelParsing() { if(!state.value.parsing) return; ++generation; nlu.cancel(); parseJob?.cancel(); mutable.update { it.copy(busy=false,parsing=false,message="已取消解析") };hold.complete() }
    fun cancelDraft() {
        if(state.value.busy) return
        nameGeneration++; nameJob?.cancel()
        mutable.update { it.copy(draft=null,editorOpen=false,originalRaw="",resolvingName=false,message="已取消草稿，未保存") }
    }
    fun editName(name: String) {
        if(state.value.busy) return
        val d=state.value.draft ?: return
        val id=++nameGeneration
        nameJob?.cancel()
        val changed=d.withData(d.data.copy(itemName=name.take(80)))
        mutable.update { it.copy(draft=changed,resolvingName=true) }
        nameJob=viewModelScope.launch {
            try {
                val rebound=factory.changeName(changed,name.take(80))
                if(id==nameGeneration) mutable.update { s ->
                    val latest=s.draft ?: return@update s
                    s.copy(draft=latest.withData(latest.data.copy(current=rebound.data.current,
                        proposedLocation=if(latest.data.locationExplicit) latest.data.proposedLocation else rebound.data.proposedLocation)),resolvingName=false)
                }
            } catch(e: CancellationException) { throw e }
            catch(_: Exception) { if(id==nameGeneration) mutable.update { it.copy(message="无法核对物品名称，请重试") } }
        }
    }
    private fun edit(block: (OperationDraft) -> OperationDraft) {
        if(state.value.busy) return
        mutable.update { it.copy(draft=it.draft?.let(block),message=null) }
    }
    fun editLocation(value: String) = edit { it.withData(it.data.copy(proposedLocation=value.take(200),locationExplicit=true)) }
    fun setAddInventory(value: Boolean) = edit { factory.changeInventoryMode(it,value) }
    fun editCount(value: String) = edit { if(it is AddUnitsDraft) factory.changeCount(it,value.take(4)) else it }
    fun editUnitLabel(value: String) = edit { it.withData(it.data.copy(unitLabel=value.take(16))) }
    fun editExpiry(key: String,value: String) = edit { d -> d.withData(d.data.copy(units=d.data.units.map { if(it.key==key) it.copy(expiryDate=value.take(10)) else it })) }
    fun reviewIssues(value: Boolean) = edit { it.withData(it.data.copy(reviewedIssues=value)) }
    fun confirmDraft() {
        val s=state.value; val d=s.draft ?: return
        if(s.busy || s.resolvingName) return
        val errors=DraftValidator.errors(d)
        if(errors.isNotEmpty()) { mutable.update { it.copy(message=errors.joinToString("；")) }; return }
        mutable.update { it.copy(busy=true) }
        viewModelScope.launch {
            try {
                val result=repository.confirm(d)
                if(!result.replay) recordHistory("draft-${d.data.id}",result.item,when {
                    d is AddUnitsDraft -> "增加 ${d.data.units.size}${d.data.unitLabel.ifBlank { "份" }}${result.item.name}"
                    d.data.current==null -> "记录 ${result.item.name} · ${result.item.location.ifBlank { "位置未记录" }}"
                    result.noOp -> "确认 ${result.item.name}的位置未变"
                    else -> "修改 ${result.item.name}位置 · ${d.data.current?.location.orEmpty().ifBlank { "未记录" }} → ${result.item.location}"
                })
                mutable.update { it.copy(draft=null,editorOpen=false,originalRaw="",detail=result.item,message=when {
                    result.replay -> "这份草稿此前已确认，已显示当前记录"
                    result.noOp -> "位置没有变化，已保留当前记录"
                    else -> "已保存确认的信息"
                }) }
            } catch(e: Exception) { mutable.update { it.copy(message=e.message ?: "保存失败，请重试") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun openItem(id: Long) {
        if(state.value.busy || state.value.draft != null || state.value.itemEdit != null) return
        mutable.update { it.copy(busy=true) }
        viewModelScope.launch {
            try { val item=repository.findById(id);mutable.update { it.copy(detail=item,pendingDeletion=null,message=if(item==null) "这个物品已无法找到" else null) } }
            catch(_: Exception) { mutable.update { it.copy(message="暂时无法读取物品") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun beginItemEdit(expiryId: Long? = null) {
        val s=state.value;val item=s.detail ?: return
        if(s.busy || s.pendingDeletion!=null || (expiryId!=null && item.units.none {it.id==expiryId})) return
        cancelSpeech()
        mutable.update {it.copy(itemEdit=ItemEditDraft(item),editExpiryId=expiryId,detail=null,message=null)}
    }
    fun editStoredName(value: String) {if(!state.value.busy) mutable.update {it.copy(itemEdit=it.itemEdit?.copy(name=value.take(80)),message=null)}}
    fun editStoredLocation(value: String) {if(!state.value.busy) mutable.update {it.copy(itemEdit=it.itemEdit?.copy(location=value.take(200)),message=null)}}
    fun editStoredExpiry(id: Long,value: String) {
        if(state.value.busy) return
        mutable.update {s->val d=s.itemEdit ?: return@update s
            if(id !in d.expiryDates) s else s.copy(itemEdit=d.copy(expiryDates=d.expiryDates+(id to value.take(10))),message=null)
        }
    }
    fun editStoredAddedCount(value: String) {
        if(!state.value.busy) mutable.update {it.copy(itemEdit=it.itemEdit?.withAddedCount(value.take(4)),message=null)}
    }
    fun editStoredAddedExpiry(key: String,value: String) {
        if(state.value.busy) return
        mutable.update {s->val d=s.itemEdit ?: return@update s
            s.copy(itemEdit=d.copy(addedUnits=d.addedUnits.map {if(it.key==key) it.copy(expiryDate=value.take(10)) else it}),message=null)
        }
    }
    // Called only after the edit screen's per-unit confirmation dialog.
    fun confirmStoredUnitRemoval(id: Long) {
        if(state.value.busy) return
        mutable.update {s->val d=s.itemEdit ?: return@update s
            if(d.original.units.none {it.id==id}) s else s.copy(
                itemEdit=d.copy(confirmedRemovedUnitIds=d.confirmedRemovedUnitIds+id),message=null)
        }
    }
    fun undoStoredUnitRemoval(id: Long) {
        if(!state.value.busy) mutable.update {s->s.copy(itemEdit=s.itemEdit?.let {
            it.copy(confirmedRemovedUnitIds=it.confirmedRemovedUnitIds-id)},message=null)}
    }
    fun cancelItemEdit() {
        val s=state.value;val d=s.itemEdit ?: return
        if(s.busy) return
        mutable.update {it.copy(itemEdit=null,editExpiryId=null,detail=items.value.rows.firstOrNull {it.id==d.original.id} ?: d.original,message=null)}
    }
    fun saveItemEdit() {
        val s=state.value;val d=s.itemEdit ?: return
        if(s.busy) return
        val errors=d.errors()
        if(errors.isNotEmpty()) {mutable.update {it.copy(message=errors.joinToString("；"))};return}
        mutable.update {it.copy(busy=true)}
        viewModelScope.launch {
            try {
                val item=repository.updateItem(d)
                if(item!=d.original) recordHistory("edit-item-${UUID.randomUUID()}",item,
                    "调整 ${item.name}的信息 · 库存 ${d.original.quantity} → ${item.quantity} 份" +
                        if(d.addedUnits.isNotEmpty() || d.confirmedRemovedUnitIds.isNotEmpty())
                            "（新增 ${d.addedUnits.size}，删除 ${d.confirmedRemovedUnitIds.size}）" else "")
                mutable.update {it.copy(itemEdit=null,editExpiryId=null,detail=item,message="已保存调整")}
            } catch(e: CancellationException) {throw e
            } catch(e: Exception) {mutable.update {it.copy(message=e.message ?: "保存失败，请重试")}}
            finally {mutable.update {it.copy(busy=false)}}
        }
    }
    fun addInventoryFromDetail() {
        val s=state.value;val item=s.detail ?: return
        if(s.busy || s.pendingDeletion!=null) return
        cancelSpeech();mutable.update {it.copy(busy=true)}
        viewModelScope.launch {
            try {
                val current=checkNotNull(repository.findById(item.id)) {"物品已不存在，请返回列表"}
                val text="增加${current.name}"
                val draft=factory.create(NluResult.ProposeAddUnits(current.name,null,null,current.location,null),text)
                check(draft.data.current?.id==current.id) {"记录已变化，请重新打开物品卡"}
                mutable.update {it.copy(input=text,originalRaw="",draft=draft,editorOpen=true,detail=null,message=null)}
            } catch(e: CancellationException) {throw e
            } catch(e: Exception) {mutable.update {it.copy(message=e.message ?: "无法打开库存草稿")}}
            finally {mutable.update {it.copy(busy=false)}}
        }
    }
    fun requestDelete(unitId: Long) {
        if(state.value.busy) return
        val item=state.value.detail ?: return
        val unit=item.units.singleOrNull { it.id==unitId } ?: return
        mutable.update { it.copy(pendingDeletion=PendingDeletion(item.name,unit)) }
    }
    fun cancelDelete() { if(!state.value.busy) mutable.update { it.copy(pendingDeletion=null) } }
    fun confirmDelete() {
        val s=state.value; val p=s.pendingDeletion ?: return
        if(s.busy) return
        mutable.update { it.copy(busy=true) }
        viewModelScope.launch {
            try {
                val item=repository.deleteInventoryUnit(p.unit.itemId,p.unit)
                recordHistory("delete-unit-${p.unit.id}",item,"删除一份 ${item.name} · 到期 ${p.unit.expiryDate ?: "未记录"}")
                mutable.update { it.copy(detail=item,pendingDeletion=null,message="已删除这一份库存，物品记录保留") }
            } catch(e: Exception) { mutable.update { it.copy(pendingDeletion=null,message=e.message ?: "删除失败") } }
            finally { mutable.update { it.copy(busy=false) } }
        }
    }
    fun startSpeech(): Boolean {
        if(state.value.busy || session!=null || state.value.itemEdit!=null) return false
        val input=speechInput ?: return false
        session="ASR-${UUID.randomUUID()}"
        mutable.update { it.copy(draft=null,detail=null,message=null,speech=it.speech.copy(permissionMessage=null)) }
        input.startListening(session!!)
        return true
    }
    fun stopSpeech() { speechInput?.stopListening() }
    private fun cancelCapture() { session=null; speechInput?.cancel(); mutable.update { it.copy(speech=it.speech.copy(speechState=SpeechRecognitionState.Idle)) } }
    fun cancelSpeech() { if(!hold.abort()) cancelCapture() }
    fun dismissDetail() { if(!state.value.busy) mutable.update { it.copy(detail=null,pendingDeletion=null,message=null) } }
    fun leaveEditor() { if(state.value.parsing) cancelParsing();cancelDraft() }
    fun onPagePaused() { cancelSpeech();if(state.value.parsing) cancelParsing() }
    fun setDebugAudioEnabled(enabled: Boolean) { speechInput?.setDebugAudioEnabled(enabled); mutable.update { it.copy(speech=it.speech.copy(debugAudioEnabled=enabled)) } }
    fun onPermissionDenied() { mutable.update { it.copy(message="请允许麦克风权限，也可以直接输入文字",speech=it.speech.copy(permissionMessage="请允许麦克风权限，也可以直接输入文字")) } }
    fun onPageStopped() = onPagePaused()
    override fun onCleared() { cancelSpeech();speechInput?.release(); nlu.release(); super.onCleared() }
}

data class HistoryUiState(val rows: List<HistoryRecord> = emptyList(), val error: String? = null)
data class ItemListUiState(val rows: List<ItemState> = emptyList(),val loading: Boolean = true,val error: String? = null)

data class SpeechUiState(val speechState: SpeechRecognitionState = SpeechRecognitionState.Idle,
    val availability: SpeechAvailability? = null, val speechMetrics: AsrMetrics? = null,
    val permissionMessage: String? = null,val debugAudioEnabled: Boolean = false)
