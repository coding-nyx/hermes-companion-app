package app.hermes.desk

import androidx.compose.ui.test.*
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.VoiceStreamState
import app.hermes.companion.model.MessageRole
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class DeskConnectionsAcceptanceTest {
    @get:Rule(order = 0) val quiet = DeskQuietEntryRule()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<DeskActivity>()
    private fun ready(vm: DeskViewModel) {
        rule.waitUntil(30_000) { vm.state.value.connected && vm.state.value.binding != null && !vm.state.value.loading && !vm.state.value.connecting || vm.state.value.error != null }
        assertNull(vm.state.value.error)
    }

    @Test fun connectionsAndSetupDoNotStartSensorsOrChangeSavedConversation() {
        val vm = ViewModelProvider(rule.activity)[DeskViewModel::class.java]
        ready(vm); val binding = vm.state.value.binding
        rule.onNodeWithText("Connections").assertIsEnabled().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Your connections").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Continue with ChatGPT").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Connect Grok").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Close connections").performScrollTo()
        rule.onNodeWithText("Close connections").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Voice setup and test").performClick()
        rule.onNodeWithText("Voice setup").assertIsDisplayed()
        rule.onNodeWithText("Test speaker").assertExists()
        rule.onNodeWithText("Close voice setup").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Device presets").performClick()
        rule.onNodeWithText("Open browser").assertIsDisplayed()
        rule.onNodeWithText("Open tablet settings").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertEquals(binding, vm.state.value.binding)
        assertEquals(VoiceStreamState.IDLE, vm.state.value.voice)
        assertFalse(vm.state.value.microphoneReady); assertFalse(vm.state.value.visionSharing)
    }

    @Test fun nativeGrokRoundTripAndHermesRestore() {
        assumeTrue("Opt-in live model test", InstrumentationRegistry.getArguments().getString("native_grok") == "true")
        val vm = ViewModelProvider(rule.activity)[DeskViewModel::class.java]
        ready(vm); val previous = vm.state.value.binding
        try {
            rule.runOnUiThread { vm.useGrok() }
            rule.waitUntil(45_000) { vm.state.value.backend == "Grok" && vm.state.value.connected && !vm.state.value.loading || vm.state.value.error != null }
            assertNull(vm.state.value.error); assertEquals("Grok", vm.state.value.backend)
            rule.runOnUiThread { vm.coordinator.newThread() }
            rule.waitUntil(30_000) { vm.state.value.canSend || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            val image = ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output); output.toByteArray() }
            bitmap.recycle()
            rule.runOnUiThread { vm.coordinator.send("What color fills the attached image? Answer with just the color.", image) }
            rule.waitUntil(90_000) { !vm.state.value.working && !vm.state.value.turnActive || vm.state.value.error != null }
            assertNull(vm.state.value.error)
            val replies = vm.state.value.messages.filter { it.role == MessageRole.ASSISTANT }.map { it.text }
            assertTrue("Native fixture reply: ${replies.map { it.take(240) }}", replies.any { it.lowercase().contains("red") })
            assertFalse(vm.state.value.capabilities.companionChat); assertFalse(vm.state.value.capabilities.decisions)
            val nativeBinding = vm.state.value.binding
            rule.runOnUiThread { vm.coordinator.refreshHistory() }
            rule.waitForIdle(); assertEquals(nativeBinding, vm.state.value.binding)
        } finally {
            rule.runOnUiThread { vm.stopVoice(); vm.useHermes() }
            rule.waitUntil(30_000) { vm.state.value.backend == "Hermes" && !vm.state.value.loading && vm.state.value.connected || vm.state.value.error != null }
        }
        assertNull(vm.state.value.error); assertEquals(previous, vm.state.value.binding)
        assertFalse(vm.state.value.microphoneReady); assertFalse(vm.state.value.visionSharing)
    }

    @Test fun browserPresetLaunchesOnlyTheTabletBrowserAndNeverSendsAModelPrompt() {
        val vm = ViewModelProvider(rule.activity)[DeskViewModel::class.java]
        ready(vm)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val launches = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                val selected = intent.selector ?: intent
                if (selected.action == Intent.ACTION_MAIN && selected.hasCategory(Intent.CATEGORY_APP_BROWSER)) {
                    launches.incrementAndGet()
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, null)
                }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        val before = vm.state.value.messages
        try {
            rule.runOnUiThread { vm.send("Open browser on this tablet") }
            rule.waitUntil(5_000) { launches.get() == 1 }
            assertEquals(before, vm.state.value.messages)
            assertFalse(vm.state.value.turnActive)
            assertFalse(vm.state.value.microphoneReady); assertFalse(vm.state.value.visionSharing)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
