package app.hermes.desk

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.VoiceStreamState
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Opt-in live-host acceptance. No credentials are passed in arguments or printed to output. */
class DeskLabAcceptanceTest {
    @get:Rule val rule = createAndroidComposeRule<DeskActivity>()

    @Test fun savedConnectionRestoresWithoutOpeningTheMicrophone() {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("lab_origin").orEmpty()
        val session = args.getString("lab_session").orEmpty()
        assumeTrue(origin.isNotBlank() && session.isNotBlank())
        val vm = ViewModelProvider(rule.activity)[DeskViewModel::class.java]
        rule.waitUntil(25_000) { vm.state.value.connected && !vm.state.value.loading && vm.state.value.session?.id == session || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertEquals(origin, vm.state.value.origin)
        assertEquals(session, vm.state.value.session?.id)
        assertTrue(vm.state.value.messages.any { it.role == app.hermes.companion.model.MessageRole.ASSISTANT && it.text.isNotBlank() })
        assertEquals(VoiceStreamState.IDLE, vm.state.value.voice)
        rule.onNodeWithText("MIC OFF").assertIsDisplayed()
    }

    @Test fun connectCreateAndRunOneHarmlessLabTurn() {
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("lab_origin").orEmpty()
        assumeTrue("Live host testing requires an explicit lab_origin", origin.isNotBlank())
        val profile = args.getString("lab_profile", "ash")!!
        val vm = ViewModelProvider(rule.activity)[DeskViewModel::class.java]
        rule.waitUntil(20_000) { !vm.state.value.connecting && !vm.state.value.loading }
        if (!vm.state.value.connected || vm.state.value.origin != origin) {
            rule.runOnIdle { vm.coordinator.disconnect() }
            rule.onNodeWithText("Dashboard address").performTextReplacement(origin)
            rule.onNodeWithText("Connect to Hermes").performClick()
        }
        rule.waitUntil(20_000) { vm.state.value.connected && !vm.state.value.loading || vm.state.value.error != null }
        assertTrue("Connection failed: ${vm.state.value.error}", vm.state.value.connected)
        assertNull(vm.state.value.error)
        val currentName = vm.state.value.profiles.first { it.id == vm.state.value.profile }.displayName
        val selectedName = vm.state.value.profiles.first { it.id == profile }.displayName
        rule.onNodeWithText("$currentName ▾").performClick()
        rule.onAllNodesWithText(selectedName).onLast().performClick()
        rule.waitUntil(20_000) { vm.state.value.profile == profile && !vm.state.value.loading || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        val requestedSession = args.getString("lab_session").orEmpty()
        if (requestedSession.isBlank()) rule.onNodeWithText("New").performClick()
        else {
            val existing = vm.state.value.sessions.first { it.id == requestedSession }
            rule.runOnIdle { vm.coordinator.openSession(existing) }
        }
        rule.waitUntil(20_000) { vm.state.value.session != null && !vm.state.value.loading || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        val session = vm.state.value.session ?: error("No desk conversation was created")
        assertEquals(profile, session.profileId)
        println("DESK_LAB_SESSION session=${session.id} profile=${session.profileId} origin=$origin")
        val previousAnswers = vm.state.value.messages.filter { it.role == app.hermes.companion.model.MessageRole.ASSISTANT }.map { it.id }
        if (args.getString("lab_send", "true") == "true") {
            rule.onNodeWithText("Say what’s on your mind").performTextInput("Hi Ash, this is my new desk companion on the Lenovo tablet. Say hello in one short sentence.")
            rule.onNodeWithText("Send").performClick()
            rule.waitUntil(120_000) { !vm.state.value.working && !vm.state.value.turnActive || vm.state.value.error != null }
        }
        assertNull("Turn failed", vm.state.value.error)
        assertFalse("Delivery was not confirmed", vm.state.value.uncertain)
        assertTrue("No assistant reply was shown", vm.state.value.messages.any { it.text.isNotBlank() && it.role == app.hermes.companion.model.MessageRole.ASSISTANT &&
            (args.getString("lab_send", "true") != "true" || it.id !in previousAnswers) })
        assertEquals(VoiceStreamState.IDLE, vm.state.value.voice)
        rule.onNodeWithText("Ambient").performClick()
        rule.onNodeWithText("Show details").assertIsDisplayed().performClick()
        println("DESK_LAB_ACCEPTANCE session=${session.id} profile=${session.profileId} origin=$origin")
    }
}
