package dev.local.physicalmemory.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.local.physicalmemory.domain.MemoryResult
import dev.local.physicalmemory.domain.model.ItemRecord
import dev.local.physicalmemory.ui.theme.MemoryTheme
import dev.local.physicalmemory.voice.SpeechEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val UpdatedTimeFormat = DateTimeFormatter.ofPattern("MM月dd日 HH:mm", Locale.CHINA)

@Composable
fun HomeScreen(
    state: HomeUiState,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetryRecords: () -> Unit,
    onCandidateSelected: (Long) -> Unit,
    onEngineSelected: (SpeechEngine) -> Unit = {},
    onMicrophone: () -> Unit = {},
    onCancelSpeech: () -> Unit = {},
    onPermissionSettings: () -> Unit = {},
    onDebugAudioChanged: (Boolean) -> Unit = {},
) {
    val focusManager = LocalFocusManager.current
    val submit = {
        if (state.canSubmit) {
            focusManager.clearFocus()
            onSubmit()
        }
    }
    Scaffold { insets ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(insets).imePadding().testTag("home-list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "heading") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("物品记忆", style = MaterialTheme.typography.headlineMedium)
                    Text("记住东西放在哪里", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item(key = "command") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = state.input,
                        onValueChange = onInputChanged,
                        modifier = Modifier.fillMaxWidth().testTag("command-input")
                            .onPreviewKeyEvent { event ->
                                if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) {
                                    submit()
                                    true
                                } else false
                            },
                        enabled = !state.isSubmitting,
                        label = { Text("记录位置，或询问位置") },
                        placeholder = { Text("钥匙放在玄关柜") },
                        supportingText = { Text("例如：钥匙放在玄关柜 / 钥匙在哪") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { submit() }),
                    )
                    Button(onClick = submit, enabled = state.canSubmit,
                        modifier = Modifier.fillMaxWidth().testTag("submit-button")) {
                        if (state.isSubmitting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("提交")
                    }
                }
            }
            state.result?.let { result ->
                item(key = "result") {
                    Card(
                        modifier = Modifier.fillMaxWidth().testTag("result-panel")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(result.title, style = MaterialTheme.typography.titleMedium)
                            Text(result.text, modifier = Modifier.testTag("result-text"), style = MaterialTheme.typography.bodyLarge)
                            result.suggestions.forEach { item ->
                                Button(
                                    onClick = { onCandidateSelected(item.id) },
                                    enabled = !state.isSubmitting,
                                    modifier = Modifier.fillMaxWidth().testTag("candidate-${item.id}"),
                                ) { Text(item.name) }
                            }
                        }
                    }
                }
            }
            item(key = "speech") {
                SpeechPanel(state, onEngineSelected, onMicrophone, onCancelSpeech, onPermissionSettings, onDebugAudioChanged)
            }
            item(key = "recent-heading") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("最近记录", style = MaterialTheme.typography.titleLarge)
                    Text("仅保存在本机 · 最近 20 件", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            when {
                state.recordsError != null -> item(key = "records-error") {
                    Column {
                        Text(state.recordsError, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetryRecords) { Text("重新加载") }
                    }
                }
                state.isLoadingRecords -> item(key = "records-loading") {
                    Text("正在读取记录…")
                }
                state.recentItems.isEmpty() -> item(key = "empty") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("还没有记录任何物品", style = MaterialTheme.typography.titleMedium)
                        Text("试试输入：“钥匙放在玄关柜”", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.recentItems, key = { it.id }) { item ->
                Column(Modifier.fillMaxWidth().testTag("record-${item.name}"),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(item.name, style = MaterialTheme.typography.titleMedium)
                    Text(item.location, modifier = Modifier.testTag("location-${item.name}"),
                        style = MaterialTheme.typography.bodyLarge)
                    Text("更新于 " + UpdatedTimeFormat.format(Instant.ofEpochMilli(item.updatedAt).atZone(ZoneId.systemDefault())),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    HorizontalDivider(Modifier.padding(top = 10.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 720, locale = "zh")
@Composable
private fun HomePreview() {
    val time = 1_788_336_000_000L
    MemoryTheme {
        HomeScreen(
            state = HomeUiState(isLoadingRecords = false, result = MemoryResult("找到啦", "钥匙在玄关柜"),
                recentItems = listOf(
                    ItemRecord(1, "钥匙", "玄关柜", time, time),
                    ItemRecord(2, "护照", "第二个抽屉", time, time),
                    ItemRecord(3, "SD卡", "相机包", time, time),
                )),
            onInputChanged = {}, onSubmit = {}, onRetryRecords = {}, onCandidateSelected = {},
        )
    }
}
