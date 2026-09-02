package dev.local.physicalmemory

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.InventoryViewModel
import dev.local.physicalmemory.voice.processMemory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class NluLifecycleTest {
    @Test fun warmUpTenCallsAndForegroundReuse(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nluLifecycle")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build()
        val engine=Qwen3NluEngine({LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}"))},
            ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use { it.readText() })
        lateinit var model:InventoryViewModel
        inst.runOnMainSync { model=InventoryViewModel(RoomInventoryRepository(db),engine) }
        V2ValidationActivity.factory={model}
        fun shell(command:String) { inst.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() } }
        val report=JSONObject();val rows=JSONArray()
        try {
            shell("am start -W -n dev.local.physicalmemory/.V2ValidationActivity")
            val start=System.nanoTime();engine.warmUp();report.put("coldWarmUpMs",(System.nanoTime()-start)/1_000_000)
            repeat(10) { i ->
                if(i==5) {
                    shell("input keyevent KEYCODE_HOME")
                    Thread.sleep(1200)
                    shell("am start -W -n dev.local.physicalmemory/.V2ValidationActivity")
                }
                val result=engine.parse(if(i%2==0) "牛奶在哪" else "增加三袋牛奶",LocalDate.of(2026,9,2)).getOrThrow()
                assertTrue(if(i%2==0) result is NluResult.OpenItem else result is NluResult.ProposeAddUnits)
                val m=engine.metrics.value!!;assertTrue(m.reused);assertEquals(0L,m.modelLoadMs)
                rows.put(JSONObject().put("round",i+1).put("totalNluMs",m.totalNluMs).put("reused",m.reused)
                    .put("cachedPromptTokens",m.cachedPromptTokens).put("pssKb",processMemory().pssKb))
                report.put("calls",rows);File(ctx.filesDir,"nlu-lifecycle.json").writeText(report.toString(2))
            }
            report.put("homeAndForegroundAfterRound",5);report.put("success",true)
            File(ctx.filesDir,"nlu-lifecycle.json").writeText(report.toString(2))
        } finally { V2ValidationActivity.factory=null;engine.release();db.close() }
    }
}
