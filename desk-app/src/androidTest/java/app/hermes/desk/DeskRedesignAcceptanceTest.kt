package app.hermes.desk

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import app.hermes.companion.VoiceStreamState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Checks the saved real configuration without sending a prompt or opening sensors. */
class DeskRedesignAcceptanceTest {
    @get:Rule(order = 0) val quiet = DeskQuietEntryRule()
    @get:Rule(order = 1) val rule = createAndroidComposeRule<DeskActivity>()
    @Test fun deskPerchRestoresLabAndKeepsHistoryAndCameraOffHome() {
        val vm = ViewModelProvider(rule.activity)[DeskViewModel::class.java]
        rule.waitUntil(30_000) { vm.state.value.connected && !vm.state.value.loading && vm.state.value.binding != null || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        val binding = vm.state.value.binding ?: error("Saved conversation did not restore")
        assertEquals(VoiceStreamState.IDLE, vm.state.value.voice)
        assertFalse(vm.state.value.visionSharing)
        val cameraPermissionBefore = ContextCompat.checkSelfPermission(rule.activity, Manifest.permission.CAMERA)
        rule.onNode(hasContentDescription("Desk robot:", substring = true)).assertIsDisplayed()
        rule.onNodeWithText("Say what’s on your mind").assertDoesNotExist()
        rule.onNodeWithText("History").performClick()
        rule.onNodeWithText("Say what’s on your mind").assertIsDisplayed()
        rule.onNodeWithText("Close history").performClick()
        rule.onNodeWithText("Say what’s on your mind").assertDoesNotExist()
        rule.onNodeWithText("Camera").performClick()
        rule.onNodeWithText("Agent vision").assertIsDisplayed()
        rule.onNodeWithText("Share with agent").assertIsDisplayed()
        assertFalse(vm.state.value.visionSharing)
        assertEquals(cameraPermissionBefore, ContextCompat.checkSelfPermission(rule.activity, Manifest.permission.CAMERA))
        rule.onNodeWithText("Close camera controls").performClick()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Speech service").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertEquals(binding, vm.state.value.binding)
        assertFalse(vm.state.value.microphoneReady)
    }
}
