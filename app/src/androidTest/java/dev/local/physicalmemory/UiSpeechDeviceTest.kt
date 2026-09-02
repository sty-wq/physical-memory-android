package dev.local.physicalmemory

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.k2fsa.sherpa.onnx.WaveReader
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.draft.DraftFactory
import dev.local.physicalmemory.history.InMemoryHistoryStore
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.*
import dev.local.physicalmemory.ui.voice.*
import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicInteger

class UiSpeechDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=inst.targetContext
    private fun tag(name:String)=compose.onNodeWithTag(name)
    private fun exists(name:String)=compose.onAllNodesWithTag(name).fetchSemanticsNodes().isNotEmpty()
    private fun shell(cmd:String) {inst.uiAutomation.executeShellCommand(cmd).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
    private fun shot(name:String) {
        compose.waitForIdle();Thread.sleep(300)
        inst.uiAutomation.takeScreenshot()?.let { b -> File(ctx.filesDir,"ui-$name.png").outputStream().use {b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle() }
    }
    private fun down(vm:InventoryViewModel) {
        tag("hold-to-talk").assertIsDisplayed().performTouchInput {down(center)}
        compose.waitUntil(15000) {vm.hold.state.value.phase==HoldPhase.Recording}
    }
    private fun up() {tag("hold-to-talk").performTouchInput {up(0)}}
    private fun move(dyDp:Float) {val px=dyDp*ctx.resources.displayMetrics.density;tag("hold-to-talk").performTouchInput {moveTo(Offset(center.x,center.y+px))}}
    private fun fixture(speech:SpeechInput,nlu:NluEngine,block:(InventoryViewModel,RoomInventoryRepository)->Unit) {
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build();val repo=RoomInventoryRepository(db);val store=ViewModelStore()
        lateinit var vm:InventoryViewModel
        inst.runOnMainSync {vm=InventoryViewModel(repo,nlu,speech,date={LocalDate.of(2026,9,2)},historyStore=InMemoryHistoryStore());store.put("real-ui",vm)}
        V2ValidationActivity.factory={vm}
        try {
            shell("am start -W -n dev.local.physicalmemory/.V2ValidationActivity")
            compose.waitUntil(60000) {exists("home-screen") && vm.hold.state.value.ready}
            block(vm,repo)
        } finally {V2ValidationActivity.factory=null;inst.runOnMainSync {store.clear()};db.close()}
    }
    @Test fun realMicrophoneCancelPauseBackgroundAndScreenOff():Unit {
        assumeTrue(InstrumentationRegistry.getArguments().getString("uiRealMicrophone")=="true")
        val decodes=AtomicInteger();val starts=AtomicInteger();val closes=AtomicInteger();val saves=AtomicInteger()
        val speech=Qwen3AsrSpeechInput(decoderFactory={val real=Qwen3Runtime(Qwen3Model.directory(ctx));object:OfflineAsrDecoder {
            override fun decode(samples:FloatArray,sampleRate:Int):String {decodes.incrementAndGet();return real.decode(samples,sampleRate)}
            override fun close()=real.close()
        }},recorderFactory={val real=AndroidPcmRecorder(ctx);object:PcmRecorder {
            override fun start() {real.start();starts.incrementAndGet()}
            override fun read(buffer:ShortArray)=real.read(buffer)
            override fun stop()=real.stop()
            override fun close() {real.close();closes.incrementAndGet()}
        }},saveAudio={_,_->saves.incrementAndGet();null},debugAllowed=true,minimumRecordDurationMs=400,requireExplicitStop=true)
        speech.setDebugAudioEnabled(true)
        val nlu=FakeNluEngine {_,_->NluResult.Unknown()}
        fixture(speech,nlu) {vm,_->
            down(vm);Thread.sleep(600);shot("real-mic-recording");move(-140f);shot("real-mic-cancel-armed");up()
            compose.waitUntil(5000) {closes.get()==starts.get()};assertEquals(0,decodes.get())
            down(vm);Thread.sleep(500)
            shell("am start -W -a android.settings.SETTINGS")
            compose.waitUntil(5000) {closes.get()==starts.get() && vm.hold.state.value.phase==HoldPhase.Idle}
            shell("am start -W -n dev.local.physicalmemory/.V2ValidationActivity")
            compose.waitUntil(10000) {runCatching {exists("hold-to-talk")}.getOrDefault(false)}
            tag("hold-to-talk").performTouchInput {cancel()}
            down(vm);Thread.sleep(500);shell("input keyevent KEYCODE_HOME")
            compose.waitUntil(5000) {closes.get()==starts.get() && vm.hold.state.value.phase==HoldPhase.Idle}
            shell("am start -W -n dev.local.physicalmemory/.V2ValidationActivity")
            compose.waitUntil(10000) {runCatching {exists("hold-to-talk")}.getOrDefault(false)}
            tag("hold-to-talk").performTouchInput {cancel()}
            val secure=(ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
            var screenOffTested=false
            if(!secure) {
                down(vm);Thread.sleep(500);shell("input keyevent KEYCODE_SLEEP")
                compose.waitUntil(5000) {closes.get()==starts.get() && vm.hold.state.value.phase==HoldPhase.Idle}
                shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard")
                shell("am start -W -n dev.local.physicalmemory/.V2ValidationActivity")
                compose.waitUntil(10000) {runCatching {exists("hold-to-talk")}.getOrDefault(false)}
                tag("hold-to-talk").performTouchInput {cancel()};screenOffTested=true
            }
            assertEquals(0,decodes.get());assertEquals(0,nlu.calls);assertEquals(0,saves.get())
            assertTrue(vm.history.value.rows.isEmpty());assertNull(vm.state.value.draft)
            assertEquals(starts.get(),closes.get());tag("home-screen").assertExists()
            File(ctx.filesDir,"ui-real-microphone.json").writeText(JSONObject().put("realMicrophone",true).put("realAsrRuntimeLoaded",true)
                .put("micStarts",starts.get()).put("micCloses",closes.get()).put("asrDecodes",decodes.get()).put("nluCalls",nlu.calls)
                .put("savedAudio",saves.get()).put("historyRows",vm.history.value.rows.size).put("pauseTested",true).put("backgroundTested",true)
                .put("deviceSecure",secure).put("screenOffAndWakeTested",screenOffTested).toString(2))
        }
    }
    @Test fun savedHumanVoiceHoldReleaseRestoreAndQueryUseRealModels():Unit {
        assumeTrue(InstrumentationRegistry.getArguments().getString("uiRealReplay")=="true")
        var waveName="ASR-50c47139-b02d-4db6-8948-5dc2d2b901e0.wav"
        val decodes=AtomicInteger()
        val speech=Qwen3AsrSpeechInput(decoderFactory={val real=Qwen3Runtime(Qwen3Model.directory(ctx));object:OfflineAsrDecoder {
            override fun decode(samples:FloatArray,sampleRate:Int):String {decodes.incrementAndGet();return real.decode(samples,sampleRate)}
            override fun close()=real.close()
        }},recorderFactory={
            val wave=WaveReader.readWaveFromFile(File(ctx.filesDir,"asr_debug/$waveName").absolutePath)
            val pcm=ShortArray(wave.samples.size) {(wave.samples[it]*32768).toInt().coerceIn(-32768,32767).toShort()}
            object:PcmRecorder {
                var pos=0;@Volatile var stopped=false
                override fun start(){}
                override fun read(buffer:ShortArray):Int {
                    Thread.sleep(100);if(stopped)return 0
                    buffer.fill(0);val n=minOf(buffer.size,pcm.size-pos);pcm.copyInto(buffer,0,pos,pos+n);pos+=n
                    return buffer.size
                }
                override fun stop() {stopped=true};override fun close(){stopped=true}
            }
        },minimumRecordDurationMs=400,requireExplicitStop=true)
        val nlu=Qwen3NluEngine({LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}"))},ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use {it.readText()})
        fixture(speech,nlu) {vm,repo->
            down(vm);Thread.sleep(2900);up()
            compose.waitUntil(90000) {exists("draft-screen") && vm.state.value.draft!=null}
            assertEquals(1,decodes.get());assertEquals("防潮箱",vm.state.value.draft!!.data.proposedLocation)
            tag("draft-screen").performScrollToNode(hasTestTag("draft-item"));tag("draft-item").performTextReplacement("R8")
            shot("real-voice-draft")
            tag("draft-screen").performScrollToNode(hasTestTag("confirm-draft"));tag("confirm-draft").performClick()
            compose.waitUntil(5000) {vm.state.value.detail?.name=="R8"}
            tag("detail-list").performScrollToNode(hasTestTag("close-detail"));tag("close-detail").performClick()
            compose.waitUntil(5000) {!exists("item-detail-sheet")}
            // CancelArmed can be reversed before release, with exactly one additional decode.
            down(vm);move(-140f);Thread.sleep(500);move(0f);Thread.sleep(2400);up()
            compose.waitUntil(90000) {exists("draft-screen") && vm.state.value.draft!=null}
            assertEquals(2,decodes.get());tag("draft-back").performClick()
            compose.waitUntil(5000) {exists("home-screen")}
            runBlocking {repo.confirm(DraftFactory(repo).create(NluResult.ProposeAddUnits("钥匙",2,"把","玄关柜",null),"fixture"))}
            waveName="ASR-ae466c51-5d30-407f-bcf5-31f44c75f49f.wav"
            down(vm);Thread.sleep(2200);up()
            compose.waitUntil(90000) {exists("item-detail-sheet") && vm.state.value.detail?.name=="钥匙"}
            assertEquals(3,decodes.get());assertEquals(2,vm.state.value.detail!!.quantity)
            shot("real-voice-query")
            // The same real device in landscape exercises constrained height and sheet scrolling.
            inst.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE}
            compose.waitUntil(10000) {ctx.resources.configuration.orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE}
            val last=vm.state.value.detail!!.units.last()
            tag("detail-list").performScrollToNode(hasTestTag("delete-unit-${last.id}"))
            tag("delete-unit-${last.id}").performClick();tag("delete-confirmation").assertExists();shot("delete-landscape")
            tag("cancel-delete").performClick();shot("detail-landscape")
            inst.runOnMainSync {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).single().requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED}
            File(ctx.filesDir,"ui-real-replay.json").writeText(JSONObject().put("inputKind","saved_user_voice_replayed_as_pcm_during_real_touch_hold")
                .put("freshMicrophone",false).put("asrDecodes",decodes.get()).put("realNlu",true).put("draftAfterRelease",true)
                .put("cancelArmedRestore",true).put("manualNameCorrection","R八 → R8").put("queryItem","钥匙").put("queryQuantity",2).toString(2))
        }
    }
}
