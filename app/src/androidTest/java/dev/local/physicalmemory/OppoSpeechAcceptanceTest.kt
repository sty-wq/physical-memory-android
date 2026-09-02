package dev.local.physicalmemory

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.draft.DraftFactory
import dev.local.physicalmemory.history.InMemoryHistoryStore
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.*
import dev.local.physicalmemory.ui.voice.HoldPhase
import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real OPPO speech into the existing UI, isolated data. Live capture requires explicit opt-in. */
class OppoSpeechAcceptanceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=inst.targetContext
    private val out get()=File(ctx.filesDir,"oppo-speech").apply {mkdirs()}
    private fun tag(s:String)=compose.onNodeWithTag(s)
    private fun exists(s:String)=compose.onAllNodesWithTag(s).fetchSemanticsNodes().isNotEmpty()
    private fun now()=System.nanoTime()/1_000_000
    private fun shell(cmd:String)=inst.uiAutomation.executeShellCommand(cmd).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use {r->r.readText()}
    }
    private fun sample(name:String) {File(out,"$name-meminfo.txt").writeText(shell("dumpsys meminfo ${ctx.packageName}"))}
    private fun shot(name:String) {
        compose.waitForIdle()
        inst.uiAutomation.takeScreenshot()?.let {b->File(out,"$name.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
    }
    @Test fun guidedLiveMicrophone() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("oppoLive")=="true" && args.getString("oppoSaveAudio")=="true")
        runSession(replayName=null)
    }
    @Test fun tenSavedOppoSpeechPipelines() {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("oppoTenRounds")=="true")
        runSession(requireNotNull(args.getString("oppoWave")))
    }
    private fun runSession(replayName:String?) {
        assertEquals("OPPO",Build.MANUFACTURER);assertEquals("PKT110",Build.MODEL)
        if(replayName!=null) require('/' !in replayName && replayName.endsWith(".wav"))
        val pcm=replayName?.let {
            val wave=WaveReader.readWaveFromFile(File(out,"audio/$it").absolutePath)
            require(wave.sampleRate==16000)
            ShortArray(wave.samples.size) {i->(wave.samples[i]*32768).toInt().coerceIn(-32768,32767).toShort()}
        }
        val kind=if(pcm==null) "live" else "replay"
        val records=File(out,"$kind.jsonl").apply {writeText("")}
        val errors=File(out,"$kind-errors.jsonl").apply {writeText("")}
        var asrFailures=0
        val loadedAsr=AtomicInteger();val loadedNlu=AtomicInteger();val decodeCount=AtomicInteger()
        val micStarts=AtomicInteger();val micCloses=AtomicInteger();val releaseAt=AtomicLong()
        val audioDone=AtomicBoolean();val pipeline=AtomicReference<PipelineTiming?>();val latestNlu=AtomicReference<JSONObject?>()
        val asrLoad=AtomicLong();val nluLoad=AtomicLong()
        val speech=Qwen3AsrSpeechInput(decoderFactory={
            val start=now();val real=Qwen3Runtime(Qwen3Model.directory(ctx));asrLoad.set(now()-start);loadedAsr.incrementAndGet()
            object:OfflineAsrDecoder {
                override fun decode(samples:FloatArray,sampleRate:Int):String {decodeCount.incrementAndGet();return real.decode(samples,sampleRate)}
                override fun close()=real.close()
            }
        },recorderFactory={
            audioDone.set(false)
            if(pcm==null) {
                val real=AndroidPcmRecorder(ctx)
                object:PcmRecorder {
                    override val bufferSizeBytes get()=real.bufferSizeBytes
                    override fun start() {real.start();micStarts.incrementAndGet()}
                    override fun read(buffer:ShortArray)=real.read(buffer)
                    override fun stop()=real.stop()
                    override fun close() {real.close();micCloses.incrementAndGet()}
                }
            } else object:PcmRecorder {
                var offset=0
                override fun start() {}
                override fun read(buffer:ShortArray):Int {
                    if(offset>=pcm.size) {audioDone.set(true);Thread.sleep(10);buffer.fill(0,0,160);return 160}
                    val n=minOf(buffer.size,pcm.size-offset);pcm.copyInto(buffer,0,offset,offset+n);offset+=n
                    Thread.sleep(n*1000L/16000);return n
                }
                override fun stop() {};override fun close() {}
            }
        },saveAudio=DebugWavStore(File(out,"audio"))::save,debugAllowed=true,memory=::processMemory,
            minimumRecordDurationMs=400,requireExplicitStop=true)
        speech.setDebugAudioEnabled(pcm==null)
        val measuredSpeech=object:SpeechInput by speech {
            override fun stopListening() {releaseAt.set(now());speech.stopListening()}
        }
        val nlu=Qwen3NluEngine({val start=now();LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}")).also {
            nluLoad.set(now()-start);loadedNlu.incrementAndGet()
        }},ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use {it.readText()},observer={text,raw,m,error ->
            latestNlu.set(JSONObject().put("input",text).put("raw",raw).put("schemaValid",error==null)
                .put("error",error ?: JSONObject.NULL).put("modelLoadMs",m.modelLoadMs).put("prefillMs",m.prefillMs)
                .put("ttftMs",m.ttftMs).put("decodeMs",m.decodeMs).put("totalNluMs",m.totalNluMs).put("modelReused",m.reused))
        })
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build();val repo=RoomInventoryRepository(db)
        runBlocking {repo.confirm(DraftFactory(repo).create(NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱",null),"isolated fixture"))}
        val store=ViewModelStore();lateinit var vm:InventoryViewModel
        inst.runOnMainSync {vm=InventoryViewModel(repo,nlu,measuredSpeech,pipelineObserver={pipeline.set(it)},historyStore=InMemoryHistoryStore());store.put("oppo",vm)}
        V2ValidationActivity.factory={vm}
        try {
            shell("am start -W -n ${ctx.packageName}/.V2ValidationActivity")
            compose.waitUntil(90000) {exists("home-screen") && vm.hold.state.value.ready}
            runBlocking {nlu.warmUp()};sample("$kind-both-ready")
            val prompts=if(pcm==null) listOf("R8放在防潮箱","AD200放在器材柜","70-200放在防潮箱","XM5放在桌子上","增加三袋牛奶","牛奶在冰箱","牛奶在哪","GoPro放在器材柜") else List(10) {"saved OPPO WAV replay"}
            prompts.forEachIndexed {i,prompt ->
                pipeline.set(null);latestNlu.set(null);releaseAt.set(0)
                File(out,"progress.json").writeText(JSONObject().put("mode",kind).put("round",i+1).put("prompt",prompt).put("status","ready_for_speech").toString())
                if(pcm!=null) {
                    tag("hold-to-talk").performTouchInput {down(center)}
                    if(i==9) {
                        compose.waitUntil(10000) {vm.hold.state.value.phase==HoldPhase.Recording}
                        val drag=140f*ctx.resources.displayMetrics.density
                        tag("hold-to-talk").performTouchInput {moveTo(androidx.compose.ui.geometry.Offset(center.x,center.y-drag))}
                        compose.waitUntil(5000) {vm.hold.state.value.phase==HoldPhase.CancelArmed}
                        tag("hold-to-talk").performTouchInput {moveTo(center)}
                        compose.waitUntil(5000) {vm.hold.state.value.phase==HoldPhase.Recording}
                    }
                    compose.waitUntil(15000) {audioDone.get()}
                    tag("hold-to-talk").performTouchInput {up(0)}
                    if(i==0) {
                        compose.waitUntil(5000) {vm.hold.state.value.phase==HoldPhase.Processing}
                        shot("replay-processing")
                    }
                }
                val deadline=now()+if(pcm==null) 600_000 else 120_000
                while(pipeline.get()==null && now()<deadline) {
                    Thread.sleep(100)
                    val metrics=speech.metrics.value
                    val error=metrics?.error
                    if(error!=null && releaseAt.get()>0) {
                        // Preserve every failed attempt. A live speaker may retry the same prompt;
                        // a replay failure still fails the stability run immediately.
                        errors.appendText(JSONObject().put("round",i+1).put("prompt",prompt).put("error",error.toString())
                            .put("audioMs",metrics.recordDurationMs).put("decodeMs",metrics.decodeMs)
                            .put("wav",metrics.debugWavPath?.let {File(it).name} ?: JSONObject.NULL).toString()+"\n")
                        asrFailures++
                        File(out,"progress.json").writeText(JSONObject().put("mode",kind).put("round",i+1)
                            .put("prompt",prompt).put("status","retry_current_prompt").put("error",error.toString()).toString())
                        if(pcm!=null || asrFailures>=5) error("ASR failed: $error")
                        releaseAt.set(0)
                    }
                }
                val t=checkNotNull(pipeline.get()) {"Waiting for speech timed out at round ${i+1}"}
                compose.waitUntil(10000) {!vm.state.value.busy}
                val a=checkNotNull(speech.metrics.value);checkNotNull(a.resultText)
                val record=JSONObject().put("round",i+1).put("source",if(pcm==null) "OPPO live microphone" else "saved OPPO microphone WAV replay")
                    .put("prompt",prompt).put("groundTruthStatus",if(pcm==null) "awaiting user confirmation" else "replay")
                    .put("asrText",a.resultText).put("wav",a.debugWavPath?.let {File(it).name} ?: replayName ?: JSONObject.NULL)
                    .put("fingerReleaseAt",releaseAt.get()).put("recordingStoppedAt",a.recordingStoppedAt)
                    .put("asrFinalAt",t.asrFinal).put("nluStartAt",t.nluStart).put("nluFinalAt",t.nluFinal)
                    .put("draftReadyAt",if(vm.state.value.draft!=null) t.draftReady else JSONObject.NULL).put("resultReadyAt",t.draftReady)
                    .put("releaseToAsrMs",t.asrFinal?.minus(releaseAt.get())).put("asrFinalToNluFinalMs",t.asrFinal?.let {t.nluFinal-it})
                    .put("releaseToResultMs",t.draftReady-releaseAt.get()).put("asrDecodeMs",a.decodeMs).put("audioMs",a.recordDurationMs)
                    .put("asrModelReused",a.modelReused).put("nlu",latestNlu.get()).put("pssKb",processMemory().pssKb)
                    .put("nativeHeapKb",processMemory().nativeHeapKb).put("draft",vm.state.value.draft?.data?.itemName ?: JSONObject.NULL)
                    .put("detail",vm.state.value.detail?.name ?: JSONObject.NULL).put("message",vm.state.value.message ?: JSONObject.NULL)
                records.appendText(record.toString()+"\n")
                File(out,"progress.json").writeText(JSONObject().put("mode",kind).put("round",i+1).put("status","result_ready").toString())
                if(i==0) sample("$kind-G_after_round_1")
                if(i==prompts.lastIndex) sample("$kind-H_after_${prompts.size}_rounds")
                shot("$kind-round-${i+1}")
                if(pcm==null) Thread.sleep(2000)
                inst.runOnMainSync {vm.cancelDraft();vm.dismissDetail();vm.inputChanged("")}
                compose.waitUntil(10000) {exists("home-screen")}
                if(pcm!=null && i==4) {
                    val pid=android.os.Process.myPid()
                    shell("input keyevent KEYCODE_HOME")
                    Thread.sleep(1000)
                    shell("am start -W -n ${ctx.packageName}/.V2ValidationActivity")
                    compose.waitUntil(10000) {exists("home-screen") && vm.hold.state.value.ready}
                    assertEquals(pid,android.os.Process.myPid())
                    assertEquals(1,loadedAsr.get());assertEquals(1,loadedNlu.get())
                    File(out,"replay-background.json").writeText(JSONObject().put("sameProcess",true)
                        .put("asrLoads",loadedAsr.get()).put("nluLoads",loadedNlu.get()).put("returnedHome",true).toString(2))
                }
            }
            assertEquals(1,loadedAsr.get());assertEquals(1,loadedNlu.get())
            if(pcm!=null) assertEquals(prompts.size,decodeCount.get()) else assertTrue(decodeCount.get()>=prompts.size)
            assertEquals(micStarts.get(),micCloses.get())
            File(out,"$kind-summary.json").writeText(JSONObject().put("rounds",prompts.size).put("asrLoads",loadedAsr.get()).put("nluLoads",loadedNlu.get())
                .put("asrColdLoadMs",asrLoad.get()).put("nluColdLoadMs",nluLoad.get()).put("micStarts",micStarts.get()).put("micCloses",micCloses.get())
                .put("asrDecodes",decodeCount.get()).put("asrFailedAttempts",asrFailures)
                .put("isolatedDb",true).put("cancelArmedRestoreTested",pcm!=null).toString(2))
            File(out,"progress.json").writeText(JSONObject().put("mode",kind).put("status","complete").toString())
        } finally {V2ValidationActivity.factory=null;inst.runOnMainSync {store.clear()};db.close()}
    }
}
