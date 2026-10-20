package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hermes.companion.data.local.DraftOwner
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.*
import app.hermes.companion.profiles.ProfileBottomSheet
import app.hermes.companion.rooms.RoomCreateSheet
import app.hermes.companion.settings.*
import java.io.File
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Real screen functions with inert callbacks; no networking, permissions or device control. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpenCanvasScreensRenderTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var screen by mutableStateOf(base())
    private var view by mutableStateOf("shell")
    private val session = SessionRef("fixture", "coder", "Release plan", 0)
    private val profiles = listOf(ProfileRef("coder", "Coder", "C"), ProfileRef("research", "Researcher", "R"))
    private fun base() = CompanionState(origin = "http://fixture", hostName = "Lab", activeProfileId = "coder",
        pairingPhase = PairingPhase.PAIRED, deviceId = "fixture", companionAvailability = CompanionAvailability.AVAILABLE)
    private fun mount(fontScale: Float) {
        org.robolectric.RuntimeEnvironment.setFontScale(fontScale)
        rule.activity.actionBar?.hide()
        rule.setContent { CompanionTheme {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                Box(Modifier.fillMaxSize()) {
                    when (view) {
                        "settings" -> SettingsBottomSheet(screen, {}, { _, _ -> }, {}, {}, onToggleStay = {},
                            onNtfyTopicChange = {}, onSaveNtfy = {}, onDisconnect = {}, onDismiss = {})
                        "profiles" -> ProfileBottomSheet(profiles, "coder", {}, {}, {})
                        "models" -> ModelBottomSheet(ModelCatalog("fixture", "custom", listOf(ModelOption("fixture", "custom", "Fixture model"))), "fixture", onSelectModel = { _, _ -> }, onDismiss = {})
                        "room" -> RoomCreateSheet(profiles, {}, {})
                        "session" -> SessionBottomSheet(session, false, false, {}, {}, {}, {}, {})
                        "context" -> SessionContextBottomSheet(SessionContextPanel("http://fixture", session, 1, false, error = "Connection lost. Refresh to try again."), {}, {})
                        "controls" -> SessionControlsBottomSheet(SessionControlsPanel(DraftOwner("http://fixture", "coder", session.id), session, 1, loading = false,
                            settings = NativeSessionSettings("fixture", "custom", "high", "normal")), {}, { _, _, _ -> }, {}, {}, {})
                        "voice" -> VoiceControlsSheet(screen.copy(voiceStreamState = VoiceStreamState.MUTED, voiceMicrophoneMuted = true), {}, {}, {}, {}, {})
                        "lock" -> BiometricLockOverlay({})
                        else -> CompanionShell(screen, onOriginChange = {}, onConnect = {}, onSelectProfile = {}, onTab = {},
                            onOpenSession = {}, onNewThread = {}, onCloseChat = {}, onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {}, imeVisible = false)
                    }
                }
            }
        } }
        rule.mainClock.advanceTimeBy(500)
    }
    @After fun restoreFontScale() { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    private fun capture(name: String) {
        rule.waitForIdle()
        val decor = rule.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        rule.runOnIdle {
            val canvas = Canvas(bitmap)
            val windows = android.view.inspector.WindowInspector.getGlobalWindowViews()
                .filter { it.isShown && it.width > 0 && it.height > 0 }
            if (windows.isEmpty()) decor.draw(canvas) else windows.forEach { root -> root.draw(canvas) }
        }
        val file = File("build/reports/open-canvas/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun checkModules(font: Float) {
        mount(font)
        listOf(MainTab.THREADS to "threads.list", MainTab.CONSOLE to "agents.screen", MainTab.REVIEW to "review.screen",
            MainTab.REMINDERS to "reminders.screen", MainTab.PROFILES to "profiles.list", MainTab.GATEWAY to "gateway.surface",
            MainTab.DEVICE to "device.surface").forEach { (tab, tag) ->
            rule.runOnIdle { screen = base().copy(tab = tab, profiles = profiles) }
            rule.mainClock.advanceTimeBy(500)
            rule.onNodeWithTag(tag).assertIsDisplayed()
            rule.onNodeWithTag("nav.more").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            capture("${tab.name.lowercase()}-${font}x")
        }
    }
    private fun populated() = base().copy(
        profiles = listOf(ProfileRef("coder", "Developer", "D", "provider/long-model-name", sessionCount = 18),
            ProfileRef("research", "Research and planning", "R", "provider/research-model", sessionCount = 7)),
        sessions = listOf(SessionRef("design", "coder", "Improve the mobile conversation layout", 1760000000000, messageCount = 12, pinned = true),
            SessionRef("release", "coder", "Release checklist and Android build verification", 1759999000000, unread = true, messageCount = 26),
            SessionRef("notes", "coder", "A very long conversation title that should truncate gracefully instead of pushing out its timestamp", 1759998000000, messageCount = 8)),
        cronJobs = listOf(CronJob("daily", "Daily project summary and outstanding work", scheduleDisplay = "Every weekday at 09:00", nextRunAt = "Tomorrow, 09:00", profile = "coder", lastStatus = "ok"),
            CronJob("weekly", "Weekly dependency review", scheduleDisplay = "Every Friday at 18:00", enabled = false, profile = "research")),
        gitStatus = GitStatus(branch = "codex/mobile-layout-repair", stagedFiles = listOf("app/src/main/java/app/hermes/companion/CompanionShell.kt"),
            modifiedFiles = listOf("feature-chat/src/main/java/app/hermes/companion/chat/ChatScreen.kt"), untrackedFiles = listOf("docs/design-review.md")),
        agentSessions = listOf(AgentSession("agent-one", "codex", title = "Review Android layouts", cwd = "/work/hermes-companion-app", status = "running", lastLine = "Checking the conversation controls"),
            AgentSession("agent-two", "claude", title = "Review release checklist and prepare a summary", cwd = "/work/hermes", status = "waiting_input")),
        hud = GatewayHud(gateway = HudState.ON, api = HudState.ON),
        modelCatalog = ModelCatalog("fixture-model", "custom", listOf(ModelOption("fixture-model", "custom", "Fixture model"), ModelOption("another-model-with-a-long-identifier", "second", "Another model"))),
        savedGateways = listOf(SavedGateway("development", "Development host", "https://development.example.test", isActive = true), SavedGateway("research", "Research host", "https://research.example.test")),
        hostMetrics = HostMetrics(cpu = HostCpuMetrics(24.5, 8)),
        deviceLabel = "Samsung Galaxy S22", deviceLane = true, a11yBound = true, notifyGranted = true, overlayGranted = true,
        nlsBound = true, protectedCustom = listOf("com.example.bank"), keepA11yAvailable = true,
    )
    private fun reviewPopulated(font: Float, suffix: String = "") {
        mount(font)
        MainTab.entries.forEach { tab ->
            rule.runOnIdle { screen = populated().copy(tab = tab) }
            rule.mainClock.advanceTimeBy(500)
            capture("populated-${tab.name.lowercase()}-${font}x$suffix")
        }
        rule.runOnIdle { screen = populated().copy(openSessionId = "design", draft = "Keep **formatting** visible", messages = listOf(
            ChatMessage("u", MessageRole.USER, "Can you summarize the release checks?", delivery = MessageDelivery.SENT),
            ChatMessage("a", MessageRole.ASSISTANT, "The Android build is ready.\n\n- Conversation controls are tested\n- Model changes are scoped to this chat\n- Spoken validation is still pending\n\nReview `ChatScreen.kt` before the next build."))) }
        rule.mainClock.advanceTimeBy(500)
        rule.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
        rule.onNodeWithTag("chat.send").assertIsDisplayed()
        capture("populated-chat-${font}x$suffix")
    }
    @Config(qualifiers = "w360dp-h800dp-night-xhdpi")
    @Test fun populatedDarkPagesAndConversationVisualReview() = reviewPopulated(1f, "-dark")
    @Test fun populatedPagesAndConversationVisualReview() = reviewPopulated(1f)
    @Test fun populatedPagesAtDoubleTextVisualReview() = reviewPopulated(2f)

    @Test fun allExistingModulesRenderWithTheSameCanvas() = checkModules(1f)
    @Test fun largeTextKeepsEveryModuleAndNavigationReachable() = checkModules(2f)

    @Test fun connectionAuthenticationAndRecoveryStayReachableAtLargeText() {
        screen = base().copy(origin = null, authRequired = true, error = "Login expired")
        mount(2f)
        rule.onNodeWithTag("connect.username").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("connect.password").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("connect.go").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("authentication-2x")
        rule.runOnIdle { screen = base().copy(loading = true) }
        rule.onNodeWithTag("shell.switching").assertIsDisplayed()
        capture("host-switch-2x")
    }
    @Test fun pairingAndHandsPermissionStatesRemainReachableWithDoubleText() {
        screen = base().copy(tab = MainTab.DEVICE, pairingPhase = PairingPhase.IDLE)
        mount(2f)
        rule.onNodeWithTag("device.pair").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.runOnIdle { screen = screen.copy(pairingPhase = PairingPhase.WAITING, pairingCode = "123456") }
        rule.onNodeWithTag("device.code").performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithTag("device.code").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val metrics = layouts.joinToString { "width=${it.size.width}, last=${it.getBoundingBox(5).right}, end=${it.getLineEnd(0, true)}" }
        Assert.assertTrue("Every pairing digit must fit: $metrics", layouts.isNotEmpty() && layouts.all {
            !it.isLineEllipsized(0) && it.getLineEnd(0, true) == 6 && it.getBoundingBox(5).right <= it.size.width + 1f
        })
        rule.onNodeWithTag("device.copy").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { screen = base().copy(tab = MainTab.DEVICE) }
        rule.onNodeWithTag("device.a11y.enable").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { screen = screen.copy(a11yBound = true, notifyGranted = true, overlayGranted = true) }
        rule.onNodeWithTag("device.arm.go").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.runOnIdle { screen = screen.copy(arm = DeviceArm.ARMED) }
        rule.onNodeWithTag("device.disarm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        capture("hands-permissions-2x")
    }
    @Test fun sheetsKeepTheirLastActionReachableWithDoubleText() {
        mount(2f)
        listOf("settings" to "settings.disconnect", "models" to "model.sheet.close", "profiles" to "nav.profile.sheet.all",
            "room" to "room.create.cancel", "session" to "session.actions", "context" to "session.context",
            "controls" to "session.controls", "voice" to "voice.controls").forEach { (mode, tag) ->
            rule.runOnIdle { view = mode }
            rule.mainClock.advanceTimeBy(500)
            when (mode) {
                "settings", "room" -> rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
                "session" -> rule.onNodeWithText("Branch conversation").performScrollTo().assertIsDisplayed()
                "controls" -> rule.onNodeWithText("Refresh from Hermes").performScrollTo().assertIsDisplayed()
                else -> rule.onNodeWithTag(tag).assertIsDisplayed()
            }
            capture("$mode-2x")
        }
        rule.runOnIdle { view = "lock" }
        rule.onNodeWithTag("lock.unlock").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
}
