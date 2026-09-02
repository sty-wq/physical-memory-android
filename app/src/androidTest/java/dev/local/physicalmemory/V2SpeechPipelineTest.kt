package dev.local.physicalmemory

import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.k2fsa.sherpa.onnx.WaveReader
import dev.local.physicalmemory.data.database.AppDatabase
import dev.local.physicalmemory.data.repository.RoomInventoryRepository
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.*
import dev.local.physicalmemory.voice.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/** Authorized saved microphone audio → real ASR → real NLU → read-only draft, on Android. */
class V2SpeechPipelineTest {
    @Test fun savedUserWaveThroughCompletePipeline(): Unit = runBlocking {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("v2SpeechReplay")=="true")
        val inst=InstrumentationRegistry.getInstrumentation();val ctx=inst.targetContext
        val name=requireNotNull(args.getString("wav"));require('/' !in name && name.endsWith(".wav"))
        val wave=WaveReader.readWaveFromFile(File(ctx.filesDir,"asr_debug/$name").absolutePath)
        assertEquals(16000,wave.sampleRate)
        val pcm=ShortArray(wave.samples.size) { (wave.samples[it]*32768).toInt().coerceIn(-32768,32767).toShort() }
        val db=Room.inMemoryDatabaseBuilder(ctx,AppDatabase::class.java).build();val repo=RoomInventoryRepository(db)
        val store=ViewModelStore();val timing=AtomicReference<PipelineTiming>()
        val nlu=Qwen3NluEngine({LlamaNluRuntime(File(ctx.filesDir,"nlu_models/${LlamaNluRuntime.MODEL_FILE}"))},
            ctx.assets.open("nlu/nlu.gbnf").bufferedReader().use { it.readText() })
        lateinit var speech:Qwen3AsrSpeechInput
        speech=Qwen3AsrSpeechInput(decoderFactory={Qwen3Runtime(Qwen3Model.directory(ctx))},recorderFactory={
            object:PcmRecorder {
                var offset=0
                override fun start(){}
                override fun read(buffer:ShortArray):Int {
                    val n=minOf(buffer.size,pcm.size-offset)
                    pcm.copyInto(buffer,0,offset,offset+n);offset+=n
                    if(offset==pcm.size) speech.stopListening()
                    return n
                }
                override fun stop(){};override fun close(){}
            }
        },memory=::processMemory)
        lateinit var model:InventoryViewModel
        try {
            inst.runOnMainSync {
                model=InventoryViewModel(repo,nlu,speech,date={LocalDate.of(2026,9,2)},pipelineObserver={timing.set(it.copy(boundary="saved_wav_replay_stop"))})
                store.put("pipeline",model);model.startSpeech()
            }
            withTimeout(120000) { while(timing.get()==null) { delay(100); check(model.state.value.message==null) { model.state.value.message.orEmpty() } } }
            val draft=checkNotNull(model.state.value.draft)
            assertEquals("钥匙",draft.data.itemName);assertEquals("玄关柜",draft.data.proposedLocation)
            assertNull(repo.findByName("钥匙"))
            val t=timing.get();val asr=speech.metrics.value!!;val m=nlu.metrics.value!!
            assertNotNull(t.speechEnd);assertNotNull(t.asrFinal)
            assertTrue(t.asrFinal!! >= t.speechEnd!! && t.nluStart>=t.asrFinal && t.nluFinal>=t.nluStart && t.draftReady>=t.nluFinal)
            File(ctx.filesDir,"v2-speech-pipeline.json").writeText(JSONObject().apply {
                put("inputKind","previously_authorized_saved_user_microphone_wav_replay");put("freshLiveMicrophone",false)
                put("wav",name);put("audioMs",pcm.size*1000L/16000);put("asrText",asr.resultText)
                put("speechEnd",t.speechEnd);put("asrFinal",t.asrFinal);put("nluStart",t.nluStart);put("nluFinal",t.nluFinal);put("draftReady",t.draftReady)
                put("boundary",t.boundary);put("asrLatencyMs",t.asrLatency);put("nluLatencyMs",t.nluLatency);put("draftLatencyMs",t.draftLatency)
                put("speechEndToDraftReadyMs",t.speechEndToDraftReady);put("asrModelLoadMs",asr.modelLoadMs)
                put("nluModelLoadMs",m.modelLoadMs);put("promptTokens",m.promptTokens);put("prefillMs",m.prefillMs)
                put("ttftMs",m.ttftMs);put("decodeMs",m.decodeMs);put("generatedTokens",m.generatedTokens)
                put("unconfirmedDatabaseWrites",0);put("draftItem",draft.data.itemName);put("draftLocation",draft.data.proposedLocation)
            }.toString(2))
        } finally { inst.runOnMainSync { store.clear() }; db.close() }
    }
}
