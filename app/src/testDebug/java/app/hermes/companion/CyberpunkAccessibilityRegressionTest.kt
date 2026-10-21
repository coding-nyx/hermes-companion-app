package app.hermes.companion

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.domain.GatewayBook
import app.hermes.companion.model.ProfileRef
import app.hermes.companion.model.SavedGateway
import app.hermes.companion.model.SessionRef
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Synthetic Compose checks, not Android permission, TalkBack, or microphone verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w320dp-h800dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CyberpunkAccessibilityRegressionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val firstHost = SavedGateway("first", "First host", "https://first.invalid", isActive = true)
    private val secondHost = SavedGateway("second", "Second host", "https://second.invalid")
    private val actions = mutableListOf<String>()
    private fun mountVoice() {
        val session = SessionRef("one", "developer", "Current conversation", 0)
        rule.activity.actionBar?.hide()
        rule.setContent { CompanionTheme {
            CompanionShell(CompanionState(origin = firstHost.origin, hostName = firstHost.name,
                savedGateways = listOf(firstHost, secondHost), activeProfileId = "developer",
                profiles = listOf(ProfileRef("developer", "Developer", "D"), ProfileRef("research", "Research", "R")),
                openSessionId = session.id, openSessionRef = session,
                voiceStreamState = VoiceStreamState.MUTED, voiceMicrophoneMuted = true),
                onOriginChange = {}, onConnect = {}, onSelectProfile = { actions += "profile:$it" },
                onSelectGateway = { actions += "host:${it.id}" }, onTab = {}, onOpenSession = {},
                onNewThread = { actions += "new" }, onCloseChat = {}, onDraftChange = {},
                onSend = {}, onInterrupt = {}, onApproval = {}, onStopVoiceStream = { actions += "stop" },
                imeVisible = false)
        } }
    }

    @Test fun narrowLargeTextNavigationKeepsEveryHitAreaAtLeast48Dp() {
        rule.setContent { CompanionTheme { CompositionLocalProvider(LocalDensity provides Density(2f, 2f)) {
            Box(Modifier.width(320.dp)) {
                AppNavigationBar(CompanionState(tab = MainTab.THREADS), false, {}, {})
            }
        } } }
        listOf("threads", "gateway", "voice", "device", "more").forEach {
            rule.onNodeWithTag("nav.$it").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun openingAndChoosingCurrentHostDoesNotEndVoice() {
        mountVoice()
        rule.onNodeWithTag("context.host").performClick()
        rule.onNodeWithText("End voice to switch conversations?").assertDoesNotExist()
        rule.onNodeWithTag("context.host.${GatewayBook.chipId(firstHost.origin)}").performClick()
        assertTrue(actions.isEmpty())
        rule.onNodeWithTag("context.host.sheet").assertDoesNotExist()
    }

    @Test fun cancellingAHostChangePreservesVoiceAndSelection() {
        mountVoice()
        rule.onNodeWithTag("context.host").performClick()
        rule.onNodeWithTag("context.host.${GatewayBook.chipId(secondHost.origin)}").performClick()
        rule.onNodeWithText("End voice to switch conversations?").assertIsDisplayed()
        assertTrue(actions.isEmpty())
        rule.onNodeWithText("Stay here").performClick()
        assertTrue(actions.isEmpty())
        rule.onNodeWithText("End voice to switch conversations?").assertDoesNotExist()
    }

    @Test fun confirmedHostChangeEndsVoiceBeforeChangingOwnership() {
        mountVoice()
        rule.onNodeWithTag("context.host").performClick()
        rule.onNodeWithTag("context.host.${GatewayBook.chipId(secondHost.origin)}").performClick()
        rule.onNodeWithText("End voice and switch").performClick()
        assertEquals(listOf("stop", "host:second"), actions)
    }

    @Test fun currentProfileIsANoopAndDifferentProfileCanBeCancelled() {
        mountVoice()
        rule.onNodeWithTag("context.agent").performClick()
        rule.onNodeWithTag("nav.profile.sheet.developer").performClick()
        assertTrue(actions.isEmpty())
        rule.onNodeWithTag("context.agent").performClick()
        rule.onNodeWithTag("nav.profile.sheet.research").performClick()
        rule.onNodeWithText("Stay here").performClick()
        assertTrue(actions.isEmpty())
    }

    @Test fun cancelledNewChatDoesNotCreateASessionOrEndVoice() {
        mountVoice()
        rule.onNodeWithTag("header.newchat").performClick()
        rule.onNodeWithText("Stay here").performClick()
        assertTrue(actions.isEmpty())
    }
}
