package dev.local.physicalmemory

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.*
import dev.local.physicalmemory.ui.voice.HoldPhase
import dev.local.physicalmemory.voice.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Explicit final step: real lock-screen cancellation. Does not unlock or bypass device credentials. */
class SecureScreenOffTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun screenOffReleasesMicrophoneWithoutAsr():Unit {
        assumeTrue(InstrumentationRegistry.getArguments().getString("uiSecureScreenOff")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        fun shell(cmd:String) {inst.uiAutomation.executeShellCommand(cmd).use {android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()}}
        val starts=AtomicInteger();val closes=AtomicInteger();val decodes=AtomicInteger()
        val speech=Qwen3AsrSpeechInput(decoderFactory={val real=Qwen3Runtime(Qwen3Model.directory(ctx));object:OfflineAsrDecoder {
            override fun decode(samples:FloatArray,sampleRate:Int):String {decodes.incrementAndGet();return real.decode(samples,sampleRate)}
            override fun close()=real.close()
        }},recorderFactory={val real=AndroidPcmRecorder(ctx);object:PcmRecorder {
            override fun start() {real.start();starts.incrementAndGet()}
            override fun read(buffer:ShortArray)=real.read(buffer)
            override fun stop()=real.stop()
            override fun close() {real.close();closes.incrementAndGet()}
        }},minimumRecordDurationMs=400,requireExplicitStop=true)
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build();val nlu=FakeNluEngine {_,_->NluResult.Unknown()}
        val store=ViewModelStore();lateinit var vm:InventoryViewModel
        inst.runOnMainSync {vm=InventoryViewModel(RoomInventoryRepository(db),nlu,speech);store.put("lock",vm)}
        V2ValidationActivity.factory={vm}
        try {
            shell("am start -W -n dev.local.physicalmemory/.V2ValidationActivity")
            compose.waitUntil(60000) {vm.hold.state.value.ready}
            compose.onNodeWithTag("hold-to-talk").assertIsDisplayed().performTouchInput {down(center)}
            compose.waitUntil(10000) {vm.hold.state.value.phase==HoldPhase.Recording}
            Thread.sleep(700);shell("input keyevent KEYCODE_SLEEP")
            val deadline=System.nanoTime()+5_000_000_000
            // No Compose UI waits after screen-off: inspect controller/resource counters while Android is paused.
            while((closes.get()!=1 || vm.hold.state.value.phase!=HoldPhase.Idle) && System.nanoTime()<deadline) Thread.sleep(50)
            assertEquals(1,starts.get());assertEquals(1,closes.get());assertEquals(0,decodes.get());assertEquals(0,nlu.calls)
            assertEquals(HoldPhase.Idle,vm.hold.state.value.phase);assertNull(vm.state.value.draft);assertTrue(vm.history.value.rows.isEmpty())
            File(ctx.filesDir,"ui-secure-screen-off.json").writeText(JSONObject().put("screenOffActuallyExecuted",true)
                .put("microphoneStarts",starts.get()).put("microphoneReleases",closes.get()).put("asrDecodes",decodes.get())
                .put("nluCalls",nlu.calls).put("draftCreated",false).put("historyRows",0).put("deviceCredentialsBypassed",false).toString(2))
        } finally {
            V2ValidationActivity.factory=null;inst.runOnMainSync {store.clear()};db.close()
            shell("input keyevent KEYCODE_WAKEUP") // User unlocks normally; no credential interaction.
        }
    }
}
