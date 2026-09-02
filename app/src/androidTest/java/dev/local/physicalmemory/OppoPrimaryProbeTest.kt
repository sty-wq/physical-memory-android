package dev.local.physicalmemory

import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.voice.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Explicit opt-in probe. Real runtimes, no user database, no network, no system setting changes. */
class OppoPrimaryProbeTest {
    private val inst get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=inst.targetContext
    private val out get()=File(ctx.filesDir,"oppo-probe").apply {mkdirs()}
    private fun shell(cmd:String):String=inst.uiAutomation.executeShellCommand(cmd).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use {r->r.readText()}
    }
    private fun sample(name:String) {
        File(out,"$name-meminfo.txt").writeText(shell("dumpsys meminfo ${ctx.packageName}"))
        File(out,"stage.txt").writeText(name)
    }
    private fun <T> during(stage:String,block:()->T):T {
        val running=AtomicBoolean(true)
        val watcher=thread(name="oppo-memory") {
            var i=0
            while(running.get()) {sample("${stage}_${i++}");Thread.sleep(250)}
        }
        try {return block()} finally {running.set(false);watcher.join()}
    }
    @Test fun stagedResidentModelsAndNluCases() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("oppoProbe")=="true")
        assertEquals("OPPO",Build.MANUFACTURER);assertEquals("PKT110",Build.MODEL)
        shell("am start -W -n ${ctx.packageName}/.V2ValidationActivity")
        sample("A_app_only")
        val timing=JSONObject().put("baseline","debug validation host plus instrumentation; no models loaded")
        var asr:Qwen3Runtime?=null
        var nlu:LlamaNluRuntime?=null
        try {
            var start=SystemClock.elapsedRealtime()
            asr=Qwen3Runtime(Qwen3Model.directory(ctx))
            timing.put("asrColdLoadMs",SystemClock.elapsedRealtime()-start);sample("B_asr_only")
            asr.close();asr=null
            start=SystemClock.elapsedRealtime()
            nlu=LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}"))
            timing.put("nluColdLoadMs",SystemClock.elapsedRealtime()-start);sample("C_nlu_only_after_asr_release")
            start=SystemClock.elapsedRealtime()
            asr=Qwen3Runtime(Qwen3Model.directory(ctx))
            timing.put("asrSecondLoadMs",SystemClock.elapsedRealtime()-start);sample("D_both_loaded")
            val wave=WaveReader.readWaveFromFile(File(Qwen3Model.directory(ctx),"test_wavs/raokouling.wav").absolutePath)
            var asrMs=0L
            val asrText=during("E_asr_inference") {
                val began=SystemClock.elapsedRealtime()
                asr.decode(wave.samples,wave.sampleRate).also {asrMs=SystemClock.elapsedRealtime()-began}
            }
            timing.put("officialWaveDecodeMs",asrMs).put("officialWaveText",asrText)
            val grammar=ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use {it.readText()}
            val cases=listOf("R8放在防潮箱","AD200放在器材柜","70-200放在防潮箱","XM5放在桌子上","GoPro放在器材柜",
                "增加三袋牛奶","牛奶在冰箱","牛奶在桌子上","牛奶在哪","牛奶还有多少","牛奶什么时候过期","看看牛奶","我要删除牛奶","牛奶少了一袋")
            val records=JSONArray()
            cases.forEachIndexed {i,text ->
                var total=0L
                fun generate():NativeOutput {
                    val began=SystemClock.elapsedRealtime()
                    return nlu.generate(NluPrompt.build(text,LocalDate.now(),false),grammar,false).also {total=SystemClock.elapsedRealtime()-began}
                }
                val result=if(i==0) during("F_nlu_inference") {generate()} else generate()
                val parsed=runCatching {check(result.completed);NluCodec.decode(result.text)}
                val t=result.timings
                records.put(JSONObject().put("text",text).put("raw",result.text).put("completed",result.completed)
                    .put("schemaValid",parsed.isSuccess).put("parsed",parsed.getOrNull()?.toString() ?: JSONObject.NULL)
                    .put("error",parsed.exceptionOrNull()?.toString() ?: JSONObject.NULL)
                    .put("modelLoadMs",0).put("prefillMs",t[1]).put("ttftMs",t[2]).put("decodeMs",t[3])
                    .put("promptTokens",t[0]).put("generatedTokens",t[4]).put("totalNluMs",total))
                File(out,"nlu-cases.json").writeText(records.toString(2))
            }
            sample("G_after_native_checks")
            timing.put("realModelFiles",true).put("speechSource","official ASR WAV; NLU cases are typed fixture inputs")
            assertTrue((0 until records.length()).all {records.getJSONObject(it).getBoolean("schemaValid")})
        } finally {
            File(out,"load-timing.json").writeText(timing.toString(2))
            asr?.close();nlu?.close()
        }
    }
}
