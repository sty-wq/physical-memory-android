package dev.local.physicalmemory

import android.os.Bundle
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.local.physicalmemory.nlu.*
import dev.local.physicalmemory.ui.inventory.*
import java.io.File
import org.json.JSONObject
import dev.local.physicalmemory.ui.theme.MemoryTheme
import dev.local.physicalmemory.voice.*

class MainActivity : ComponentActivity() {
    private val model: InventoryViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as MemoryApplication
                val speech = Qwen3AsrSpeechInput(
                    decoderFactory = { Qwen3Runtime(Qwen3Model.directory(app)) },
                    recorderFactory = { AndroidPcmRecorder(app) },
                    saveAudio = DebugWavStore(File(app.filesDir, "asr_debug"))::save,
                    debugAllowed = BuildConfig.DEBUG, memory = ::processMemory, sink = app.asrLog::record,
                    minimumRecordDurationMs = 400, requireExplicitStop = true)
                val nlu = Qwen3NluEngine(
                    { LlamaNluRuntime(File(app.filesDir, "nlu_models/${LlamaNluRuntime.MODEL_FILE}")) },
                    app.assets.open("nlu/nlu.gbnf").bufferedReader().use { it.readText() })
                @Suppress("UNCHECKED_CAST")
                return InventoryViewModel(app.inventoryRepository, nlu, speech, historyStore=app.historyStore,
                    pipelineObserver = { timing -> if(BuildConfig.DEBUG) {
                        File(app.filesDir,"v2-pipeline.jsonl").appendText(JSONObject().apply {
                            put("speechEnd",timing.speechEnd); put("asrFinal",timing.asrFinal)
                            put("nluStart",timing.nluStart); put("nluFinal",timing.nluFinal); put("draftReady",timing.draftReady)
                            put("boundary",timing.boundary); put("speechEndToDraftReady",timing.speechEndToDraftReady)
                        }.toString()+"\n")
                    } }) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MemoryTheme {
                val state by model.state.collectAsStateWithLifecycle()
                var permissionAsked by rememberSaveable { mutableStateOf(false) }
                val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    // Permission dialogs terminate the original pointer gesture. Require a fresh hold.
                    if (!granted) model.onPermissionDenied()
                }
                InventoryScreen(state, model,
                    onHoldStart = {
                        when {
                            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ->
                                model.hold.down()
                            permissionAsked && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) ->
                                { model.onPermissionDenied();false }
                            else -> {
                                permissionAsked = true
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                                false
                            }
                        }
                    },
                    onPermissions = {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    },
                )
            }
        }
    }

    override fun onStop() {
        model.onPageStopped()
        super.onStop()
    }
    override fun onPause() {
        model.onPagePaused()
        super.onPause()
    }
}
