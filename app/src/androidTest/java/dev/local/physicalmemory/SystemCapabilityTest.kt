package dev.local.physicalmemory

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.local.physicalmemory.voice.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemCapabilityTest {
    @Test fun probeIsRuntimeBasedAndMissingServiceDoesNotCrash() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as MemoryApplication
        val capability = SystemSpeechCapability.probe(app)
        app.asrLog.probe(capability)
        instrumentation.runOnMainSync {
            val input = AndroidSpeechInput(app, capability, app.asrLog::record)
            assertEquals(capability.available || capability.onDeviceAvailable, input.availability.value.available)
            // Avoid opening an actual microphone during automated capability inspection.
            input.cancel(); input.release(); input.release()
            assertEquals(SpeechRecognitionState.Idle, input.state.value)
        }
    }
}
