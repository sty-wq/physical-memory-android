package dev.local.physicalmemory

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.voice.*
import com.k2fsa.sherpa.onnx.WaveReader
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** Real Android ARM64 inference, no main database access. Invoked explicitly with -e nluNative true. */
class NluNativeTest {
    @Test fun benchmark() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nluNative") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val thinking = args.getString("thinking") == "true"
        val reportName = args.getString("report") ?: if(thinking) "thinking" else "nonthinking"
        val file = File(ctx.filesDir,"nlu-$reportName.jsonl"); file.writeText("")
        val info = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(info)
        val storage = StatFs(ctx.filesDir.absolutePath)
        File(ctx.filesDir,"nlu-device.json").writeText(JSONObject().apply {
            put("manufacturer",Build.MANUFACTURER); put("model",Build.MODEL); put("android",Build.VERSION.RELEASE)
            put("api",Build.VERSION.SDK_INT); put("abis",JSONArray(Build.SUPPORTED_ABIS.toList()))
            put("soc",if(Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unavailable")
            put("totalRamBytes",info.totalMem); put("availableRamBytes",info.availMem)
            put("cores",Runtime.getRuntime().availableProcessors()); put("storageTotalBytes",storage.totalBytes)
            put("storageAvailableBytes",storage.availableBytes)
        }.toString(2))
        val casesFile = File(ctx.filesDir,"nlu-benchmark-cases.json")
        val cases = if(casesFile.exists()) JSONArray(casesFile.readText()) else JSONArray().put(JSONObject().put("id","smoke").put("text","牛奶在冰箱").put("current_date","2026-09-02"))
        val offset = args.getString("offset")?.toIntOrNull() ?: 0
        val limit = args.getString("limit")?.toIntOrNull() ?: cases.length()
        var currentId = ""
        val peak = AtomicLong(processMemory().pssKb)
        val running = AtomicBoolean(true)
        val monitor = thread(name="nlu-memory-monitor") { while(running.get()) {
            peak.accumulateAndGet(processMemory().pssKb, ::maxOf); Thread.sleep(200)
        } }
        val before = processMemory()
        val engine = Qwen3NluEngine({ LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}")) },
            ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use { it.readText() }, thinking,
            observer = { text, raw, m, error ->
                file.appendText(JSONObject().apply {
                    put("id",currentId); put("text",text); put("raw",raw); put("error",error ?: JSONObject.NULL)
                    put("promptVersion",NluPrompt.VERSION); put("cachedPromptTokens",m.cachedPromptTokens); put("thinking",thinking); put("modelLoadMs",m.modelLoadMs); put("promptTokens",m.promptTokens)
                    put("prefillMs",m.prefillMs); put("ttftMs",m.ttftMs); put("decodeMs",m.decodeMs)
                    put("generatedTokens",m.generatedTokens); put("totalNluMs",m.totalNluMs); put("reused",m.reused)
                    put("pssKb",processMemory().pssKb); put("peakPssKb",peak.get())
                }.toString()+"\n")
            })
        var successes = 0
        try {
            for(i in offset until minOf(cases.length(),offset+limit)) {
                val c = cases.getJSONObject(i); currentId = c.getString("id")
                val start = SystemClock.elapsedRealtime()
                val result = engine.parse(c.getString("text"),LocalDate.parse(c.optString("current_date","2026-09-02")))
                if(result.isSuccess) successes++
                if(result.isFailure && (file.readLines().lastOrNull()?.contains("\"id\":\"$currentId\"") != true))
                    file.appendText(JSONObject().put("id",currentId).put("text",c.getString("text")).put("error",result.exceptionOrNull().toString()).put("totalNluMs",SystemClock.elapsedRealtime()-start).toString()+"\n")
            }
            File(ctx.filesDir,"nlu-$reportName-memory.json").writeText(JSONObject().put("beforePssKb",before.pssKb)
                .put("nluResidentPssKb",processMemory().pssKb).put("peakPssKb",peak.get()).toString(2))
            assertTrue("No successful native NLU output", successes > 0)
        } finally { engine.release(); running.set(false); monitor.join() }
    }

    @Test fun coexist(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nluCoexist") == "true")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val values = JSONObject().put("baselinePssKb",processMemory().pssKb)
        val peak = AtomicLong(processMemory().pssKb); val running = AtomicBoolean(true)
        val monitor = thread { while(running.get()) { peak.accumulateAndGet(processMemory().pssKb,::maxOf); Thread.sleep(200) } }
        try {
            Qwen3Runtime(Qwen3Model.directory(ctx)).use { asr ->
                val wave = WaveReader.readWaveFromFile(File(Qwen3Model.directory(ctx),"test_wavs/raokouling.wav").absolutePath)
                asr.decode(wave.samples,wave.sampleRate)
                values.put("asrOnlyPssKb",processMemory().pssKb)
                LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}")).use { nlu ->
                    val grammar = ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use { it.readText() }
                    val out = nlu.generate(NluPrompt.build("牛奶在冰箱",LocalDate.of(2026,9,2),false),grammar,false)
                    assertTrue(out.completed); NluCodec.decode(out.text)
                    values.put("bothResidentPssKb",processMemory().pssKb)
                }
            }
        } finally {
            running.set(false); monitor.join()
            values.put("peakPssKb",peak.get()); values.put("afterReleasePssKb",processMemory().pssKb)
            File(ctx.filesDir,"nlu-coexist.json").writeText(values.toString(2))
        }
    }
}
