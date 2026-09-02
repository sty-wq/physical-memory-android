package dev.local.physicalmemory.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import dev.local.physicalmemory.BuildConfig
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.local.physicalmemory.voice.*

@Composable
fun SpeechPanel(state: HomeUiState, onEngineSelected: (SpeechEngine) -> Unit, onMicrophone: () -> Unit,
    onCancelSpeech: () -> Unit, onPermissionSettings: () -> Unit, onDebugAudioChanged: (Boolean) -> Unit = {}) {
    var debug by rememberSaveable { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("语音输入", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.speechAvailability.keys.forEach { engine ->
                    FilterChip(selected = state.selectedEngine == engine, onClick = { onEngineSelected(engine) },
                        label = { Text(engine.label) }, modifier = Modifier.testTag("engine-${engine.name}"))
                }
            }
            val availability = state.speechAvailability[state.selectedEngine]
            Text(availability?.detail ?: "语音未接入", style = MaterialTheme.typography.bodySmall)
            val label = when (state.speechState) {
                is SpeechRecognitionState.Initializing -> "正在准备… 点击结束"
                is SpeechRecognitionState.Listening, is SpeechRecognitionState.Partial -> "正在听… 点击结束"
                is SpeechRecognitionState.Finalizing, is SpeechRecognitionState.Recognizing -> "正在识别…"
                else -> "🎙 点击说话"
            }
            Button(onClick = onMicrophone, enabled = !state.isSubmitting && availability?.available == true &&
                state.speechState !is SpeechRecognitionState.Finalizing && state.speechState !is SpeechRecognitionState.Recognizing,
                modifier = Modifier.fillMaxWidth().testTag("microphone-button")) { Text(label) }
            if (state.speechState.isActive) TextButton(onClick = onCancelSpeech, modifier = Modifier.testTag("cancel-speech")) {
                Text(if (state.speechState is SpeechRecognitionState.Recognizing) "取消识别" else "取消录音")
            }
            Text(when (val speech = state.speechState) {
                SpeechRecognitionState.Idle -> "语音结果会自动解析，请核对草稿后确认保存"
                is SpeechRecognitionState.Initializing -> "正在初始化引擎"
                is SpeechRecognitionState.Listening -> "正在听…"
                is SpeechRecognitionState.Partial -> speech.text
                is SpeechRecognitionState.Finalizing -> state.transcription.ifBlank { "等待最终结果…" }
                is SpeechRecognitionState.Recognizing -> "正在识别…"
                is SpeechRecognitionState.Final -> "识别完成：${speech.text}"
                is SpeechRecognitionState.Error -> speech.message
            }, modifier = Modifier.testTag("speech-status"))
            state.permissionMessage?.let { message ->
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onPermissionSettings) { Text("打开应用权限设置") }
            }
            TextButton(onClick = { debug = !debug }, modifier = Modifier.testTag("asr-debug-toggle")) {
                Text(if (debug) "收起 ASR 调试信息" else "ASR 调试信息")
            }
            if (debug) {
                if (state.selectedEngine == SpeechEngine.QWEN3_ASR) {
                    Text("Model: Qwen3-ASR 0.6B INT8 · Offline · CPU / 2 threads · Auto language", style = MaterialTheme.typography.bodySmall)
                    if (BuildConfig.DEBUG) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = state.debugAudioEnabled, onCheckedChange = onDebugAudioChanged,
                                modifier = Modifier.testTag("debug-save-audio"))
                            Text("保存本机测试录音（Debug）", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(state.capabilityDetails, style = MaterialTheme.typography.bodySmall)
                state.speechAvailability.forEach { (engine, value) ->
                    Text("${engine.label}: ${value.detail} [${value.mode}]", style = MaterialTheme.typography.bodySmall)
                }
                state.speechMetrics?.let { metrics ->
                    Text("Session: ${metrics.sessionId}\nEngine: ${metrics.engine} / ${metrics.mode}\n" +
                        "Startup: ${metrics.startupLatency ?: "—"} ms\nModel load: ${metrics.modelLoadMs ?: "—"} ms\n" +
                        "First partial: ${metrics.firstPartialLatency ?: "—"} ms\nFinal: ${metrics.finalLatency ?: "—"} ms\n" +
                        "End → final: ${metrics.speechEndToFinalLatency ?: "—"} ms\nBoundary: ${metrics.speechBoundarySource ?: "未观测"}\n" +
                        "PCM samples: ${metrics.audioSamples}\nError: ${metrics.error ?: "无"} / ${metrics.rawErrorCode ?: "—"}",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("asr-metrics"))
                    metrics.modelLoadedPssKb?.let { Text("加载后应用 PSS: $it KiB", style = MaterialTheme.typography.bodySmall) }
                    metrics.peakRmsDb?.let { Text("系统回调 RMS 峰值: $it dB", style = MaterialTheme.typography.bodySmall) }
                    if (metrics.engine == SpeechEngine.QWEN3_ASR) {
                        Text("Model reused: ${metrics.modelReused ?: "—"}\nAudio: ${metrics.recordDurationMs ?: "—"} ms\n" +
                            "Decode: ${metrics.decodeMs ?: "—"} ms\nRTF: ${metrics.rtf?.let { "%.2f".format(it) } ?: "—"}\n" +
                            "停止 → Final: ${metrics.totalAfterSpeechMs ?: "—"} ms\n" +
                            "Decode peak PSS: ${metrics.memoryPeakDecode?.pssKb ?: "—"} KiB", style = MaterialTheme.typography.bodySmall)
                        metrics.debugWavPath?.let { Text("WAV: $it", style = MaterialTheme.typography.bodySmall) }
                        metrics.debugAudioError?.let { Text("测试录音保存失败：$it", color = MaterialTheme.colorScheme.error) }
                    }
                    if (metrics.speechBoundarySource == "token_timestamp_estimate")
                        Text("Sherpa 的结束时间由词元时间估计，并非实测声学结束。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
