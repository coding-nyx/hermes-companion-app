package app.hermes.companion

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Production shell with synthetic content; no prompts, settings mutations or microphone access. */
@RunWith(AndroidJUnit4::class)
class NavigationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun readableBarAndMoreNavigateWithoutChangingTheComposerOrStartingVoice() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync {
            compose.activity.setShowWhenLocked(false)
            compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val keyguard = i.targetContext.getSystemService(KeyguardManager::class.java)
        val waitMs = InstrumentationRegistry.getArguments().getString("unlockTimeoutMs")?.toLongOrNull()?.coerceIn(1_000, 300_000) ?: 90_000L
        val deadline = System.nanoTime() + waitMs * 1_000_000L
        while (keyguard.isKeyguardLocked && System.nanoTime() < deadline) delay(100)
        assertFalse("Unlock the device for navigation testing", keyguard.isKeyguardLocked)
        i.runOnMainSync { (i.targetContext.applicationContext as CompanionApp).deviceNode.arm() }
        val session = SessionRef("synthetic-navigation", "knight", "Navigation test", 0)
        var screen by mutableStateOf(CompanionState(origin = "http://lab", hostName = "Lab", activeProfileId = "knight",
            openSessionId = session.id, openSessionRef = session, draft = "Draft with **formatting**"))
        val actions = mutableListOf<String>()
        val settingChanges = mutableListOf<Pair<String, String>>()
        val modelChoices = listOf(
            app.hermes.companion.model.ModelOption("fixture-model", "first", "Fixture", reasoning = true, canDisableReasoning = null),
            app.hermes.companion.model.ModelOption("other-model", "second", "Other"))
        i.runOnMainSync {
            compose.activity.setContent { CompanionTheme {
                CompanionShell(screen, onOriginChange = {}, onConnect = {}, onSelectProfile = {},
                    onTab = { actions += it.name; screen = screen.copy(tab = it, chatMinimized = true) }, onOpenSession = {}, onNewThread = {},
                    onCloseChat = { actions += "close"; screen = screen.copy(openSessionId = null, openSessionRef = null) },
                    onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {},
                    onSessionControls = { screen = screen.copy(sessionControls = SessionControlsPanel(
                        app.hermes.companion.data.local.DraftOwner("http://lab", "knight", session.id), session, 1,
                        loading = false, settings = app.hermes.companion.model.NativeSessionSettings(
                            "fixture-model", "first", "none", "normal", modelChoices))) },
                    onSetSessionSetting = { key, value, _ ->
                        settingChanges += key to value
                        if (key == "reasoning") screen.sessionControls?.let { panel ->
                            screen = screen.copy(sessionControls = panel.copy(settings = panel.settings?.copy(reasoning = value)))
                        }
                    },
                    onDismissSessionControls = { screen = screen.copy(sessionControls = null) },
                    onToggleVoiceStream = { actions += "voice"; screen=screen.copy(voiceStreamState=VoiceStreamState.LISTENING,voiceSessionTitle=session.title) },
                    onToggleVoiceMicrophone = { val muted=!screen.voiceMicrophoneMuted; screen=screen.copy(voiceMicrophoneMuted=muted,voiceStreamState=if(muted)VoiceStreamState.MUTED else VoiceStreamState.LISTENING) },
                    onStopVoiceStream = { screen=screen.copy(voiceStreamState=VoiceStreamState.IDLE,voiceMicrophoneMuted=false) },
                    onReturnToVoiceConversation = { screen=screen.copy(tab=MainTab.THREADS,chatMinimized=false) })
            } }
        }
        compose.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
        compose.onNodeWithTag("chat.fmt.italic").assertIsDisplayed()
        compose.onNodeWithTag("header.voice").assertDoesNotExist()
        compose.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send message").assertIsDisplayed()
        listOf("attach", "slash", "voice", "send").forEach {
            compose.onNodeWithTag("chat.$it").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        fun bounds(tag: String) = compose.onNodeWithTag("chat.$tag").fetchSemanticsNode().boundsInRoot
        assertTrue(bounds("attach").bottom < bounds("slash").top)
        assertTrue(bounds("voice").bottom < bounds("send").top)
        assertTrue(bounds("draft").width > bounds("attach").width * 3)
        i.runOnMainSync {
            compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime())
        }
        listOf("threads", "console", "device", "more").forEach {
            compose.onNodeWithTag("nav.$it").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        }
        screenshot(i, "navigation-device-chat.png")
        compose.onNodeWithTag("nav.more").performClick()
        compose.onNodeWithText("Code review").assertIsDisplayed()
        compose.onNodeWithText("Schedules").assertIsDisplayed()
        compose.onNodeWithText("Hosts").assertIsDisplayed()
        compose.onNodeWithText("Profiles").assertIsDisplayed()
        screenshot(i, "navigation-device-more.png")
        compose.onNodeWithTag("nav.review").performClick()
        compose.waitUntil(5_000) { screen.tab == MainTab.REVIEW }
        compose.onNodeWithTag("nav.more").assertIsSelected()
        assertEquals(listOf("REVIEW"), actions)
        screenshot(i, "review.png")
        compose.onNodeWithTag("nav.console").performClick()
        compose.onNodeWithTag("agents.screen").assertIsDisplayed()
        screenshot(i, "agents.png")
        listOf("profiles" to "profiles.list", "gateway" to "gateway.surface", "reminders" to "reminders.screen").forEach { (route, surface) ->
            compose.onNodeWithTag("nav.more").performClick()
            compose.onNodeWithTag("nav.$route").performClick()
            compose.onNodeWithTag(surface).assertIsDisplayed()
            screenshot(i, "$route.png")
        }
        compose.onNodeWithTag("nav.device").performClick()
        compose.waitUntil(5_000) { screen.tab == MainTab.DEVICE }
        compose.onNodeWithTag("nav.device").assertIsSelected()
        assertFalse("Navigation must not open the microphone", "voice" in actions)
        i.runOnMainSync { screen = screen.copy(companionAvailability = app.hermes.companion.model.CompanionAvailability.AVAILABLE) }
        compose.onNodeWithTag("nav.threads").performClick()
        compose.onNodeWithTag("threads.new").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("threads.new").assertTextContains("New chat")
        compose.onNodeWithTag("threads.newroom").assertDoesNotExist()
        screenshot(i, "navigation-device-chat-list.png")
        compose.onNodeWithTag("threads.options").performClick()
        compose.onNodeWithText("New agent room").assertIsDisplayed()
        screenshot(i, "navigation-device-chat-options.png")
        i.runOnMainSync { screen = screen.copy(openSessionId = session.id, openSessionRef = session, chatMinimized = false) }
        compose.onNodeWithTag("chat.model.settings").assertDoesNotExist()
        compose.onNodeWithTag("chat.draft").performClick()
        compose.onNodeWithTag("chat.model.settings").assertIsDisplayed().performClick()
        // Hiding Samsung IME and attaching the modal can briefly detach both window roots.
        // Wait for the new window, then keep all regular visibility/action assertions.
        compose.waitUntil(5_000) { runCatching {
            compose.onAllNodesWithTag("composer.models").fetchSemanticsNodes().isNotEmpty()
        }.getOrDefault(false) }
        compose.onNodeWithTag("composer.models").assertIsDisplayed()
        compose.onNodeWithTag("composer.models.providers").performClick()
        compose.onNodeWithTag("composer.models.provider.second").performClick()
        compose.onNodeWithTag("composer.models.item.first/fixture-model").assertDoesNotExist()
        compose.onNodeWithTag("composer.models.item.second/other-model").performClick()
        compose.onNodeWithTag("composer.tab.reasoning").performClick()
        compose.onNodeWithTag("composer.models.reasoning").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("composer.models.reasoning").assertIsOn().performClick()
        compose.onNodeWithTag("composer.models.reasoning").assertIsOff()
        compose.onNodeWithTag("composer.reasoning.high").performScrollTo().performClick()
        compose.onNodeWithTag("composer.models.reasoning").performScrollTo().assertIsOn()
        assertEquals(listOf("model" to "other-model --provider second --session", "reasoning" to "medium", "reasoning" to "none", "reasoning" to "high"), settingChanges)
        screenshot(i, "navigation-device-composer-models.png")
        compose.onNodeWithTag("composer.models.done").performScrollTo().performClick()
        assertEquals("Draft with **formatting**", screen.draft)
        i.runOnMainSync { compose.activity.window.insetsController?.hide(android.view.WindowInsets.Type.ime()) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nav.voice").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat.voice").performTouchInput { longClick() }
        compose.waitUntil(5_000) { screen.isVoiceStreamActive }
        assertEquals(1,actions.count { it=="voice" })
        compose.onNodeWithTag("voice.stage.mute").performClick()
        assertTrue(screen.voiceMicrophoneMuted)
        compose.onNodeWithTag("nav.device").performClick()
        compose.onNodeWithTag("voice.stage").assertIsDisplayed()
        assertEquals(session.id,screen.openSessionId)
        compose.onNodeWithTag("nav.voice").performClick()
        compose.onNodeWithTag("voice.controls").assertIsDisplayed()
        screenshot(i,"navigation-device-voice-controls.png")
        compose.onNodeWithTag("voice.controls.conversation").performClick()
        compose.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
        compose.onNodeWithTag("chat.draft").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nav.voice").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("voice.stage").assertIsDisplayed()
        screenshot(i,"navigation-device-voice-keyboard.png")
        compose.onNodeWithTag("voice.stage.stop").performClick()
        assertFalse(screen.isVoiceStreamActive)
        assertEquals("Draft with **formatting**",screen.draft)
        Unit
    }

    @Test fun populatedLayoutsAndSettingsHaveDeviceCaptures() = runBlocking {
        val i = InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync { compose.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val keyguard = i.targetContext.getSystemService(KeyguardManager::class.java)
        val deadline = System.nanoTime() + 300_000_000_000L
        while (keyguard.isKeyguardLocked && System.nanoTime() < deadline) delay(100)
        assertFalse("Unlock the device for layout review", keyguard.isKeyguardLocked)
        i.runOnMainSync { (i.targetContext.applicationContext as CompanionApp).deviceNode.arm() }
        delay(6_000) // Let the safety heads-up finish its normal animation before captures.
        var state by mutableStateOf(reviewState())
        val session = state.sessions.first()
        i.runOnMainSync { compose.activity.setContent { CompanionTheme {
            CompanionShell(state, onOriginChange = {}, onConnect = {}, onSelectProfile = {},
                onTab = { state = state.copy(tab = it, chatMinimized = true) }, onOpenSession = {}, onNewThread = {},
                onCloseChat = {}, onDraftChange = { state = state.copy(draft = it) }, onSend = {}, onInterrupt = {}, onApproval = {},
                onSessionControls = { state = state.copy(sessionControls = SessionControlsPanel(
                    app.hermes.companion.data.local.DraftOwner(state.origin!!, "coder", session.id), session, 1,
                    loading = false, settings = NativeSessionSettings("fixture-model", "custom", "high", "normal",
                        listOf(ModelOption("fixture-model", "custom", "Fixture model", reasoning = true, canDisableReasoning = true))))) },
                onDismissSessionControls = { state = state.copy(sessionControls = null) })
        } } }
        MainTab.entries.forEach { tab ->
            i.runOnMainSync { state = reviewState().copy(tab = tab) }
            screenshot(i, "repair-populated-${tab.name.lowercase()}.png")
            if (tab == MainTab.DEVICE) {
                compose.onNodeWithTag("device.protected.open").performScrollTo().performClick()
                compose.onNodeWithTag("device.protected").performScrollTo().assertIsDisplayed()
                screenshot(i, "repair-device-settings.png")
            }
        }
        i.runOnMainSync { state = reviewState().copy(rooms = (1..8).map { RoomRef("room-$it", "Planning room $it") }) }
        compose.onNodeWithTag("threads.rail").performScrollToNode(hasTestTag("threads.row.${session.id}"))
        compose.onNodeWithTag("threads.row.${session.id}").assertIsDisplayed()
        screenshot(i, "repair-rooms-and-chats.png")
        i.runOnMainSync { state = reviewState().copy(openSessionId = session.id, draft = "Keep **formatting** visible", messages = listOf(
            ChatMessage("u", MessageRole.USER, "Can you summarize the release checks?", delivery = MessageDelivery.SENT),
            ChatMessage("a", MessageRole.ASSISTANT, "The Android build is ready.\n\n- Conversation controls are tested\n- Model changes are scoped to this chat\n- Spoken validation is still pending\n\nReview `ChatScreen.kt` before the next build."))) }
        screenshot(i, "repair-chat.png")
        compose.onNodeWithTag("chat.draft").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("nav.voice").fetchSemanticsNodes().isEmpty() }
        screenshot(i, "repair-chat-keyboard.png")
        compose.onNodeWithTag("chat.model.settings").performClick()
        compose.waitUntil(5_000) { runCatching { compose.onAllNodesWithTag("composer.models").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false) }
        screenshot(i, "repair-model-picker.png")
        compose.onNodeWithTag("composer.tab.reasoning").performClick()
        screenshot(i, "repair-reasoning.png")
        compose.onNodeWithTag("composer.models.done").performScrollTo().performClick()
        compose.onNodeWithTag("header.settings").performClick()
        screenshot(i, "repair-settings.png")
        compose.onNodeWithTag("settings.disconnect").performScrollTo().assertIsDisplayed()
        screenshot(i, "repair-settings-lower.png")
        compose.onNodeWithTag("settings.sheet.close").performScrollTo().performClick()
        Unit
    }

    private fun reviewState() = CompanionState(origin = "https://fixture.example.test", hostName = "Lab", activeProfileId = "coder",
        pairingPhase = app.hermes.companion.model.PairingPhase.PAIRED, deviceId = "fixture", companionAvailability = app.hermes.companion.model.CompanionAvailability.AVAILABLE).copy(
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
    @After fun disarmAfterDeviceChecks() {
        val i=InstrumentationRegistry.getInstrumentation()
        i.runOnMainSync { (i.targetContext.applicationContext as CompanionApp).deviceNode.disarm() }
    }

    private fun screenshot(i: android.app.Instrumentation, name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        i.waitForIdleSync()
        // The Compose test clock does not advance WindowManager's dialog animation.
        // Wait for its real-time fade before capturing SurfaceFlinger's screen image.
        android.os.SystemClock.sleep(600)
        val packageDeadline = System.nanoTime() + 5_000_000_000L
        while (i.uiAutomation.rootInActiveWindow?.packageName?.toString() != i.targetContext.packageName && System.nanoTime() < packageDeadline) android.os.SystemClock.sleep(100)
        assertEquals("Capture requires the Companion window", i.targetContext.packageName, i.uiAutomation.rootInActiveWindow?.packageName?.toString())
        val bitmap = requireNotNull(i.uiAutomation.takeScreenshot())
        File(i.targetContext.cacheDir, "open-canvas-$name").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
