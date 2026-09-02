package dev.local.physicalmemory.ui.voice

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun HoldToTalkButton(state: HoldToTalkState, enabled: Boolean, onDown: () -> Boolean,
    onMove: (Float) -> Unit, onUp: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val currentEnabled by rememberUpdatedState(enabled)
    val begin by rememberUpdatedState(onDown); val move by rememberUpdatedState(onMove)
    val end by rememberUpdatedState(onUp); val cancel by rememberUpdatedState(onCancel)
    val density=LocalDensity.current.density
    val label=when(state.phase) {
        HoldPhase.Preparing -> "正在准备语音…"
        HoldPhase.Idle -> if(state.ready) "按住说话" else "语音暂不可用"
        HoldPhase.Starting -> "准备录音…"
        HoldPhase.Recording -> "松开发送"
        HoldPhase.CancelArmed -> "✕ 松开取消"
        HoldPhase.Processing -> "正在处理…"
    }
    val armed=state.phase==HoldPhase.CancelArmed
    Surface(modifier.fillMaxWidth().heightIn(min=96.dp).testTag("hold-to-talk")
        .semantics { role=Role.Button;contentDescription="按住说话，松开发送，上滑取消；也可使用上方文字输入";stateDescription=label
            if(!enabled) disabled() }
        // Keep the pointer coroutine stable across Recording/CancelArmed recomposition.
        .pointerInput(Unit) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                if(!currentEnabled || !begin()) return@awaitEachGesture
                down.consume()
                var owned=true
                try {
                    while(true) {
                        val event=awaitPointerEvent()
                        val change=event.changes.firstOrNull { it.id==down.id }
                        if(change==null || event.changes.any { it.id!=down.id && it.pressed } || change.isConsumed) {
                            cancel();owned=false;break
                        }
                        move((change.position.y-down.position.y)/density)
                        change.consume()
                        if(!change.pressed) { end();owned=false;break }
                    }
                } finally { if(owned) cancel() }
            }
        }, shape=MaterialTheme.shapes.large,
        color=if(armed) MaterialTheme.colorScheme.errorContainer else if(enabled || state.phase in listOf(HoldPhase.Starting,HoldPhase.Recording))
            MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(Modifier.padding(20.dp),contentAlignment=Alignment.Center) {
            Text(label,style=MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
fun RecordingOverlay(state: HoldToTalkState, tick: () -> Unit, modifier: Modifier = Modifier) {
    val showing=state.phase in setOf(HoldPhase.Starting,HoldPhase.Recording,HoldPhase.CancelArmed)
    LaunchedEffect(showing) { if(showing) while(true) { tick();delay(100) } }
    if(!showing) return
    val cancel=state.phase==HoldPhase.CancelArmed
    // A composited visual, not a Dialog/window that would cancel the active pointer stream.
    Card(modifier.widthIn(max=300.dp).testTag("recording-overlay"),
        colors=CardDefaults.cardColors(containerColor=if(cancel) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface)) {
        Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(cancel) "✕" else "●",style=MaterialTheme.typography.headlineLarge)
            Text(if(cancel) "松开取消录音" else if(state.phase==HoldPhase.Starting) "正在打开麦克风…" else "正在听…",style=MaterialTheme.typography.titleLarge)
            Text("%02d:%02d".format(state.elapsedMs/60_000,state.elapsedMs/1000%60))
            Text(if(cancel) "滑回按钮可继续录音" else "上滑取消 · 松开发送")
        }
    }
}
