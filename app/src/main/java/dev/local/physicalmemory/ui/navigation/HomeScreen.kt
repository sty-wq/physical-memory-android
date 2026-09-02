package dev.local.physicalmemory.ui.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.local.physicalmemory.ui.voice.*

data class HomeScreenState(val text: String, val busy: Boolean, val parsing: Boolean, val message: String?, val recognizing: Boolean,val permissionDenied: Boolean = false)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(state: HomeScreenState, hold: HoldToTalkState, controller: HoldToTalkController,
    onText: (String)->Unit, onSubmit: ()->Unit, onCancelParsing: ()->Unit,
    onPress: ()->Boolean, onRetrySpeech: ()->Unit, modifier: Modifier = Modifier,onPermissions: ()->Unit = {}) {
    val focus=LocalFocusManager.current
    val keyboardVisible=WindowInsets.isImeVisible
    val active=hold.phase in setOf(HoldPhase.Starting,HoldPhase.Recording,HoldPhase.CancelArmed,HoldPhase.Processing)
    DisposableEffect(controller) { onDispose { controller.abort() } }
    Box(modifier.fillMaxSize().testTag("home-screen")) {
        Column(Modifier.fillMaxSize().imePadding().padding(horizontal=24.dp)) {
          Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top=24.dp,bottom=16.dp),
              verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Text("物品助手",style=MaterialTheme.typography.headlineLarge)
            Text("今天需要记住什么？",style=MaterialTheme.typography.titleMedium)
            OutlinedTextField(state.text,onText,Modifier.fillMaxWidth().testTag("command-input"),
                enabled=!state.busy && !active,label={Text("输入物品信息或问题")},
                placeholder={Text("例如：增加三袋牛奶，放冰箱")},minLines=3,maxLines=5)
            Button({focus.clearFocus();onSubmit()},enabled=!state.busy && !active && state.text.isNotBlank(),
                modifier=Modifier.fillMaxWidth().testTag("parse-button")) { Text(if(state.parsing) "正在理解内容…" else "提交") }
            if(state.parsing) TextButton(onCancelParsing,modifier=Modifier.testTag("cancel-parse")) { Text("取消处理") }
            if(state.recognizing) Text("正在识别语音…",Modifier.testTag("asr-processing"))
            state.message?.let { Text(it,Modifier.testTag("operation-message"),color=MaterialTheme.colorScheme.primary) }
            if(state.permissionDenied) TextButton(onPermissions,modifier=Modifier.testTag("microphone-settings")) { Text("打开麦克风权限设置") }
            hold.message?.takeIf { it!=state.message }?.let { Text(it,Modifier.testTag("hold-message")) }
            if(!hold.ready && hold.phase!=HoldPhase.Preparing) TextButton(onRetrySpeech) { Text("重试准备语音") }
            Text("只保存在本机 · 记录信息需要你确认",style=MaterialTheme.typography.bodySmall)
          }
          Column(Modifier.fillMaxWidth().padding(top=12.dp,bottom=16.dp).testTag("voice-footer"),
              verticalArrangement=Arrangement.spacedBy(10.dp)) {
            if(!keyboardVisible) Text("按住说话，松开发送；向上滑动取消。",style=MaterialTheme.typography.bodyMedium)
            HoldToTalkButton(hold,!state.busy && hold.ready && hold.phase==HoldPhase.Idle,
                onDown={focus.clearFocus();onPress()},onMove=controller::move,onUp=controller::up,
                onCancel={controller.abort()})
          }
        }
        RecordingOverlay(hold,controller::tick,Modifier.align(Alignment.TopCenter).padding(top=40.dp,start=24.dp,end=24.dp))
    }
}
