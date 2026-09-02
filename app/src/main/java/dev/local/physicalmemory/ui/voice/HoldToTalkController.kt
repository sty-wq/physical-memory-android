package dev.local.physicalmemory.ui.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class HoldPhase { Preparing, Idle, Starting, Recording, CancelArmed, Processing }
data class HoldToTalkState(val phase: HoldPhase = HoldPhase.Preparing, val ready: Boolean = false,
    val elapsedMs: Long = 0, val message: String? = null)

/** Gesture policy has no Android or audio dependencies. Times start at actual microphone readiness. */
class HoldToTalkController(private val start: () -> Boolean, private val stop: () -> Unit,
    private val cancelRecording: () -> Unit, private val clock: () -> Long = { System.nanoTime()/1_000_000 },
    val thresholdDp: Float = 96f, private val minimumMs: Long = 400) {
    private val mutable = MutableStateFlow(HoldToTalkState())
    val state = mutable.asStateFlow()
    private var held = false
    private var armed = false
    private var listeningAt: Long? = null
    fun preparing() { mutable.value = HoldToTalkState() }
    fun prepared(success: Boolean, message: String? = null) {
        mutable.value = HoldToTalkState(HoldPhase.Idle,success,message=message)
    }
    fun down(): Boolean {
        if(!state.value.ready || state.value.phase != HoldPhase.Idle) return false
        held=true;armed=false;listeningAt=null
        mutable.value=state.value.copy(phase=HoldPhase.Starting,elapsedMs=0,message=null)
        if(!start()) { complete();return false }
        return true
    }
    fun listening() {
        if(!held) return
        listeningAt=clock()
        mutable.value=state.value.copy(phase=if(armed) HoldPhase.CancelArmed else HoldPhase.Recording)
    }
    fun move(dyDp: Float) {
        if(!held) return
        armed=dyDp < -thresholdDp
        mutable.value=state.value.copy(phase=if(armed) HoldPhase.CancelArmed else if(listeningAt==null) HoldPhase.Starting else HoldPhase.Recording)
    }
    fun tick() { listeningAt?.let { if(held) mutable.value=state.value.copy(elapsedMs=(clock()-it).coerceAtLeast(0)) } }
    fun up() {
        if(!held) return
        tick()
        if(armed) { abort("已取消录音");return }
        if(listeningAt==null || state.value.elapsedMs < minimumMs) { abort("录音时间太短，请按住再说一次");return }
        held=false;mutable.value=state.value.copy(phase=HoldPhase.Processing)
        stop()
    }
    fun processing() { if(state.value.phase != HoldPhase.Preparing) mutable.value=state.value.copy(phase=HoldPhase.Processing) }
    fun complete(message: String? = null) {
        held=false;armed=false;listeningAt=null
        mutable.value=state.value.copy(phase=HoldPhase.Idle,elapsedMs=0,message=message)
    }
    fun abort(message: String? = null): Boolean {
        if(!held && state.value.phase !in setOf(HoldPhase.Processing,HoldPhase.Starting)) return false
        held=false;cancelRecording();complete(message);return true
    }
}
