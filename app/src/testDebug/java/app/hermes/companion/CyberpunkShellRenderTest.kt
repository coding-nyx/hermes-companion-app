package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.*
import app.hermes.companion.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

/** Synthetic UI fixtures only: no host requests, microphone, notifications or permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w360dp-h800dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CyberpunkShellRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val profiles = listOf(ProfileRef("developer", "Developer", "D"), ProfileRef("research", "Research", "R"))
    private fun state(tab: MainTab = MainTab.THREADS) = CompanionState(origin = "https://fixture.invalid", hostName = "Night City",
        activeProfileId = "developer", profiles = profiles, tab = tab, companionAvailability = CompanionAvailability.AVAILABLE,
        pairingPhase = PairingPhase.PAIRED, deviceId = "fixture", deviceLabel = "Preview phone",
        deviceLane = true, a11yBound = true, notifyGranted = true, nlsBound = true,
        savedGateways = listOf(SavedGateway("fixture", "Night City", "https://fixture.invalid", isActive = true)),
        sessions = listOf(SessionRef("one", "developer", "Ship the next release", 0), SessionRef("two", "developer", "Explore a new idea", 0)))
    private fun mount(screen: CompanionState = state(), scale: Float = 1f, onNew: () -> Unit = {}, onProfile: (String) -> Unit = {}) {
        org.robolectric.RuntimeEnvironment.setFontScale(scale)
        rule.activity.actionBar?.hide()
        rule.setContent { CompanionTheme { CompositionLocalProvider(LocalDensity provides Density(2f, scale)) {
            CompanionShell(screen, onOriginChange = {}, onConnect = {}, onSelectProfile = onProfile, onTab = {}, onOpenSession = {},
                onNewThread = onNew, onCloseChat = {}, onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {}, imeVisible = false)
        } } }
    }
    @After fun restoreFontScale() { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    @Test fun contextSwitchersAreExplicitAndTouchSized() {
        mount()
        rule.onNodeWithTag("context.host").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("threads.new").assertIsDisplayed()
        capture("chats-dark")
        rule.onNodeWithTag("context.host").performClick()
        rule.onNodeWithTag("context.host.sheet").assertIsDisplayed()
        capture("host-switcher-dark")
    }
    @Test fun gatewayIsDirectAndHonestAboutOptionalRelay() {
        mount(state(MainTab.GATEWAY).copy(companionAvailability = CompanionAvailability.UNAVAILABLE))
        rule.onNodeWithTag("nav.more").assertIsSelected()
        rule.onNodeWithText("Unavailable on this connection").assertIsDisplayed()
        rule.onNodeWithTag("gateway.open.chats").assertIsDisplayed()
        rule.onNodeWithTag("gateway.open.permissions").assertIsDisplayed()
        capture("gateway-dark")
    }
    @Test fun startingNewChatIsAvailableOutsideChatList() {
        var created = 0
        mount(state(MainTab.DEVICE), onNew = { created++ })
        rule.onNodeWithTag("header.options").performClick()
        rule.onNodeWithTag("header.newchat").assertIsDisplayed().performClick()
        assertEquals(1, created)
        capture("device-dark")
    }
    @Test fun inspectingAgentChooserDoesNotEndVoiceOrChangeProfile() {
        var changed = 0
        mount(state().copy(voiceStreamState = VoiceStreamState.MUTED, voiceMicrophoneMuted = true,
            openSessionId = "one", voiceSessionTitle = "Ship the next release"), onProfile = { changed++ })
        rule.onNodeWithTag("header.options").performClick()
        rule.onNodeWithTag("context.agent").performClick()
        rule.onNodeWithText("End voice to switch conversations?").assertDoesNotExist()
        assertEquals(0, changed)
    }
    @Test fun largeTypeKeepsContextAndGlobalActionsReachable() {
        mount(scale = 2f)
        rule.onNodeWithTag("context.host").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("nav.voice").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag("threads.new").assertIsDisplayed()
        capture("chats-large-text")
    }
    @Test fun agentQuickSwitcherIdentifiesCurrentSelection() {
        mount()
        rule.onNodeWithTag("header.options").performClick()
        rule.onNodeWithTag("context.agent").performClick()
        rule.onNodeWithTag("nav.profile.sheet.developer").assertIsSelected().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("nav.profile.sheet.research").assertIsNotSelected().assertHeightIsAtLeast(48.dp)
        capture("agent-switcher-dark")
        rule.onNodeWithTag("nav.profile.sheet.close").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("nav.profile.sheet").assertDoesNotExist()
    }
    @Test fun agentQuickSwitcherKeepsChoicesReachableAtDoubleText() {
        mount(scale = 2f)
        rule.onNodeWithTag("header.options").performClick()
        rule.onNodeWithTag("context.agent").performClick()
        rule.onNodeWithTag("nav.profile.sheet.research").performScrollTo().assertIsDisplayed()
        capture("agent-switcher-large-text")
    }
    @Test fun decorativeMotionIsOffByDefaultAndSystemDisableWins() {
        CompanionAppearance.setMotionEnabled(rule.activity, false)
        assertFalse(CompanionAppearance.motionEnabled(rule.activity))
        android.provider.Settings.Global.putFloat(rule.activity.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        CompanionAppearance.setMotionEnabled(rule.activity, true)
        var motion = true
        rule.setContent { CompanionTheme { motion = LocalCompanionMotion.current } }
        rule.runOnIdle { assertFalse(motion) }
        CompanionAppearance.setMotionEnabled(rule.activity, false)
    }
    private fun capture(name: String) {
        rule.waitForIdle()
        val decor = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = Canvas(bitmap)
            android.view.inspector.WindowInspector.getGlobalWindowViews().filter { it.isShown && it.width > 0 }.forEach { it.draw(canvas) }
        }
        File("build/reports/cyberpunk/$name.png").apply { parentFile?.mkdirs() }.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
