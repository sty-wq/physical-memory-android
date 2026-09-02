package dev.local.physicalmemory

import dev.local.physicalmemory.ui.voice.*
import org.junit.Assert.*
import org.junit.Test

class HoldToTalkTest {
    private var now=0L
    private var starts=0;private var stops=0;private var cancels=0
    private fun controller()=HoldToTalkController({starts++;true},{stops++},{cancels++},{now}).also { it.prepared(true) }
    @Test fun downStartsImmediatelyAndNormalReleaseStopsOnce() {
        val c=controller();assertTrue(c.down());assertEquals(1,starts)
        c.listening();assertEquals(HoldPhase.Recording,c.state.value.phase)
        now=800;c.up();c.up()
        assertEquals(1,stops);assertEquals(0,cancels);assertEquals(HoldPhase.Processing,c.state.value.phase)
    }
    @Test fun upwardThresholdArmsAndReleaseCancelsWithoutStop() {
        val c=controller();c.down();c.listening();now=900
        c.move(-95f);assertEquals(HoldPhase.Recording,c.state.value.phase)
        c.move(-110f);assertEquals(HoldPhase.CancelArmed,c.state.value.phase)
        c.up();assertEquals(0,stops);assertEquals(1,cancels);assertEquals(HoldPhase.Idle,c.state.value.phase)
    }
    @Test fun movingBackRestoresRecordingAndSubmitsOnce() {
        val c=controller();c.down();c.listening();c.move(-150f);c.move(-20f)
        assertEquals(HoldPhase.Recording,c.state.value.phase);now=500;c.up()
        assertEquals(1,stops);assertEquals(0,cancels)
    }
    @Test fun shortTapAndReleaseBeforeMicrophoneReadyBothCancel() {
        val c=controller();c.down();now=2000;c.up()
        assertEquals(0,stops);assertEquals(1,cancels)
        c.down();c.listening();now+=399;c.up()
        assertEquals(0,stops);assertEquals(2,cancels)
    }
    @Test fun processingBlocksRepeatedDownAndCancellationIsIdempotent() {
        val c=controller();c.down();c.listening();now=800;c.up()
        assertFalse(c.down());assertEquals(1,starts)
        c.abort();c.abort();assertEquals(1,cancels)
        c.complete();assertTrue(c.down())
    }
    @Test fun pointerCancelMultiTouchAndLifecycleAllUseAbortWithoutDecode() {
        val c=controller()
        repeat(3) { c.down();c.listening();now+=800;c.abort() }
        assertEquals(3,cancels);assertEquals(0,stops)
    }
    @Test fun unavailableAndPreparingDoNotStart() {
        val c=controller();c.preparing();assertFalse(c.down());c.prepared(false);assertFalse(c.down());assertEquals(0,starts)
    }
}
