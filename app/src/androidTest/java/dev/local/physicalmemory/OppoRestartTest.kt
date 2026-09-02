package dev.local.physicalmemory

import android.os.Process
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.domain.draft.DraftFactory
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.UUID

/** Run create, force-stop the app normally, then verify in another instrumentation process. */
class OppoRestartTest {
    @Test fun durableInventoryAcrossProcessRestart()=runBlocking {
        val phase=InstrumentationRegistry.getArguments().getString("oppoRestartPhase")
        assumeTrue(phase in listOf("create","verify"))
        val ctx=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(ctx.filesDir,"oppo-restart").apply {mkdirs()}
        val name=if(phase=="create") "oppo-restart-${UUID.randomUUID()}.db" else File(root,"database.txt").readText()
        require(name.matches(Regex("oppo-restart-[a-f0-9-]+\\.db")))
        val db=Room.databaseBuilder(ctx,AppDatabase::class.java,name).build()
        val repo=RoomInventoryRepository(db)
        try {
            if(phase=="create") {
                repo.confirm(DraftFactory(repo).create(NluResult.ProposeAddUnits("牛奶",3,"袋","冰箱",null),"isolated restart fixture"))
                File(root,"database.txt").writeText(name)
                File(root,"expected.txt").writeText(checkNotNull(repo.findByName("牛奶")).toString())
                File(root,"create-pid.txt").writeText(Process.myPid().toString())
            } else {
                assertNotEquals(File(root,"create-pid.txt").readText(),Process.myPid().toString())
                val item=checkNotNull(repo.findByName("牛奶"))
                assertEquals(File(root,"expected.txt").readText(),item.toString())
                Qwen3Runtime(Qwen3Model.directory(ctx)).use {asr ->
                    val wave=com.k2fsa.sherpa.onnx.WaveReader.readWaveFromFile(File(Qwen3Model.directory(ctx),"test_wavs/raokouling.wav").absolutePath)
                    assertTrue(asr.decode(wave.samples,wave.sampleRate).isNotBlank())
                    LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}")).use {nlu->
                        val grammar=ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use {it.readText()}
                        val result=nlu.generate(NluPrompt.build("牛奶在哪",LocalDate.now(),false),grammar,false)
                        assertTrue(result.completed);assertEquals(NluResult.OpenItem("牛奶"),NluCodec.decode(result.text))
                    }
                }
                File(root,"verified.json").writeText(JSONObject().put("differentProcess",true).put("isolatedPersistentDatabase",true)
                    .put("itemAndAllUnitFieldsUnchanged",true).put("quantity",item.quantity).put("asrReloadAndDecode",true).put("nluReloadAndParse",true).toString(2))
            }
        } finally {db.close()}
    }
}
