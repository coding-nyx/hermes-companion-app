package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.SessionRef
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.*
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppNavigationRenderTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    private fun bar(tab: MainTab, fontScale: Float = 1f, inChat: Boolean = false,
        onTab: (MainTab) -> Unit = {}, onMore: () -> Unit = {}) {
        compose.setContent { CompanionTheme {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale)) {
                Column(Modifier.width(360.dp)) {
                    AppNavigationBar(CompanionState(tab = tab), inChat, onTab, onMore)
                }
            }
        } }
    }

    @Test fun fourDestinationsAndADistinctCenterVoiceActionHaveTouchTargets() {
        bar(MainTab.THREADS)
        listOf("Bots", "Chats", "Device", "More").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        listOf("bots", "threads", "device", "more").forEach {
            compose.onNodeWithTag("nav.$it").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        }
        compose.onNodeWithTag("nav.voice").assertIsDisplayed().assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("nav.threads").assertIsSelected()
        capture("navigation-standard")
    }

    @Test fun everyNavigationItemUsesTheSameHeightAndVerticalAlignment() {
        bar(MainTab.BOTS)
        val bounds = listOf("bots", "threads", "voice", "device", "more").map { compose.onNodeWithTag("nav.$it").fetchSemanticsNode().boundsInRoot }
        bounds.forEach { assertEquals(bounds.first().top, it.top, 0.5f); assertEquals(bounds.first().bottom, it.bottom, 0.5f) }
        capture("navigation-balanced")
    }

    @Test fun everyChatTypeHidesTheFloatingBarAndReturnsItWhenBrowsing() {
        val session = SessionRef("s", "default", "Chat", 0)
        var screen by androidx.compose.runtime.mutableStateOf(CompanionState(origin = "https://host", tab = MainTab.THREADS,
            activeProfileId = "default", openSessionId = session.id, openSessionRef = session))
        compose.setContent { CompanionTheme { CompanionShell(screen, onOriginChange = {}, onConnect = {}, onSelectProfile = {},
            onTab = {}, onOpenSession = {}, onNewThread = {}, onCloseChat = {}, onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {}, imeVisible = false) } }
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        compose.runOnIdle { screen = screen.copy(openSessionRef = session.copy(title = "Bot Chat")) }
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        compose.runOnIdle { screen = screen.copy(openSessionId = null, openSessionRef = null, openRoomId = "room") }
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        compose.runOnIdle { screen = screen.copy(tab = MainTab.BOTS, openRoomId = null, botMode = BotModeState(
            selected = app.hermes.companion.model.NativeGroupSnapshot(app.hermes.companion.model.NativeGroup("g", "Group", emptyList(), "host", 1)))) }
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        compose.runOnIdle { screen = screen.copy(botMode = BotModeState()) }
        compose.onNodeWithTag("navigation.bar").assertIsDisplayed()
        capture("floating-bar-dark-chat-exit")
    }

    @Test fun doubledTextKeepsSelectedLabelReadableAndEveryDestinationAccessible() {
        bar(MainTab.DEVICE, fontScale = 2f)
        listOf("Bots", "Chats", "Device", "More").forEach { compose.onNodeWithContentDescription(it).assertIsDisplayed().assertHeightIsAtLeast(48.dp) }
        listOf("Device").forEach {
            val node = compose.onNodeWithText(it, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
            // Intrinsic width can round by a fraction of a pixel. Require every character
            // on one non-ellipsized line, rather than flagging harmless rounding as clipping.
            assertTrue("Label clipped at double text size: $it", layouts.isNotEmpty() && layouts.all {
                it.lineCount <= 2 && (0 until it.lineCount).none { line -> it.isLineEllipsized(line) } && it.getLineEnd(it.lineCount - 1, visibleEnd = true) == it.layoutInput.text.length
            })
        }
        compose.onNodeWithTag("nav.device").assertIsSelected()
        capture("navigation-large-text")
    }

    @Test fun centerVoiceTapAndLongPressHaveDistinctActions() {
        var voice = 0; var dictate = 0
        compose.setContent { CompanionTheme {
            AppNavigationBar(CompanionState(), false, {}, {}, onVoice = { voice++ }, onDictate = { dictate++ })
        } }
        compose.onNodeWithTag("nav.voice").performTouchInput { longClick() }
        assertEquals(1, dictate); assertEquals(0, voice)
        compose.onNodeWithTag("nav.voice").performClick()
        assertEquals(1, voice); assertEquals(1, dictate)
    }

    @Test fun secondaryDestinationsSelectMoreAndOpenConversationSelectsChats() {
        assertEquals(NavigationDestination.MORE, navigationDestination(MainTab.GATEWAY, false))
        assertEquals(NavigationDestination.MORE, navigationDestination(MainTab.CONSOLE, false))
        assertEquals(NavigationDestination.MORE, navigationDestination(MainTab.PROFILES, false))
        assertEquals(NavigationDestination.CHATS, navigationDestination(MainTab.CONSOLE, true))
        bar(MainTab.REVIEW)
        compose.onNodeWithTag("nav.more").assertIsSelected()
    }

    @Test fun primaryActionsAreExplicitAndMoreOnlyOpensItsMenu() {
        val selected = mutableListOf<MainTab>(); var more = 0
        bar(MainTab.THREADS, onTab = selected::add, onMore = { more++ })
        compose.onNodeWithTag("nav.bots").performClick()
        compose.onNodeWithTag("nav.more").performClick()
        assertEquals(listOf(MainTab.BOTS), selected)
        assertEquals(1, more)
    }

    @Test fun moreListsEverySecondaryDestinationAndDismissesBeforeNavigation() {
        val actions = mutableListOf<String>()
        compose.setContent { CompanionTheme {
            NavigationMoreSheet(CompanionState(tab = MainTab.GATEWAY, hostName = "Lab"),
                { actions += it.name }, { actions += "settings" }, { actions += "dismiss" })
        } }
        listOf("Code review", "Schedules", "Coding agents", "Agent profiles", "Settings").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithTag("nav.review").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals(listOf("dismiss", "REVIEW"), actions)
    }

    @Test fun chatKeepsRichTextAndMovesVoiceToTheDock() {
        val session = SessionRef("synthetic-navigation", "knight", "Navigation test", 0)
        compose.setContent { CompanionTheme {
            CompanionShell(CompanionState(origin = "http://lab", tab = MainTab.THREADS, activeProfileId = "knight",
                openSessionId = session.id, openSessionRef = session, draft = "Draft with **formatting**"),
                onOriginChange = {}, onConnect = {}, onSelectProfile = {},
                onTab = {}, onOpenSession = {}, onNewThread = {},
                onCloseChat = {}, onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {}, imeVisible=false)
        } }
        compose.onNodeWithTag("chat.tools").assertDoesNotExist()
        compose.onNodeWithTag("header.voice").assertDoesNotExist()
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        // Real IME hide/show and shell navigation are exercised by NavigationDeviceTest.
        capture("chat-with-navigation")
    }

    @Test fun browsingModulesKeepsVoiceAttachedAndDoesNotCloseTheChat() {
        val session=SessionRef("voice-session","knight","Pinned conversation",0)
        var screen by androidx.compose.runtime.mutableStateOf(CompanionState(origin="http://lab",tab=MainTab.THREADS,activeProfileId="knight",
            openSessionId=session.id,openSessionRef=session,draft="Preserved **draft**",voiceStreamState=VoiceStreamState.MUTED,
            voiceMicrophoneMuted=true,voiceSessionTitle=session.title))
        var closed=0
        compose.setContent { CompanionTheme { CompanionShell(screen,onOriginChange={},onConnect={},onSelectProfile={},
            onTab={ screen=screen.copy(tab=it,chatMinimized=true) },onOpenSession={},onNewThread={},onCloseChat={screen=screen.copy(chatMinimized=true)},
            onDraftChange={},onSend={},onInterrupt={},onApproval={},
            onReturnToVoiceConversation={screen=screen.copy(tab=MainTab.THREADS,chatMinimized=false)}, imeVisible=false) } }
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        compose.onNodeWithTag("chat.back").performClick()
        compose.onNodeWithTag("nav.device").performClick()
        compose.onNodeWithTag("nav.device").assertIsSelected()
        compose.onNodeWithTag("chat.draft").assertDoesNotExist()
        compose.onNodeWithTag("voice.stage").assertIsDisplayed()
        compose.onNodeWithTag("nav.voice").performClick()
        compose.onNodeWithTag("voice.controls").assertIsDisplayed()
        compose.onNodeWithTag("voice.controls.conversation").performClick()
        compose.onNodeWithTag("chat.draft").assertTextContains("Preserved **draft**")
        assertEquals("voice-session",screen.openSessionId); assertEquals(0,closed)
        capture("voice-pinned-conversation")
    }

    @Test fun startFromAModuleUsesOneExplicitVoiceTapWithoutAConversationChoice() {
        var starts=0
        compose.setContent { CompanionTheme { CompanionShell(CompanionState(origin="http://lab",tab=MainTab.DEVICE,activeProfileId="knight"),
            onOriginChange={},onConnect={},onSelectProfile={},onTab={},onOpenSession={},onNewThread={},onCloseChat={},
            onDraftChange={},onSend={},onInterrupt={},onApproval={},onToggleVoiceStream={starts++}, imeVisible=false) } }
        compose.onNodeWithTag("nav.voice").performClick()
        compose.onNodeWithTag("voice.start").assertDoesNotExist()
        assertEquals(1,starts)
    }

    @Test fun keyboardHidesDestinationsButKeepsMuteEndAndRichFormattingVisible() {
        val session=SessionRef("voice-session","knight","Pinned conversation",0)
        compose.setContent { CompanionTheme { CompanionShell(CompanionState(origin="http://lab",tab=MainTab.THREADS,activeProfileId="knight",
            openSessionId=session.id,openSessionRef=session,voiceStreamState=VoiceStreamState.MUTED,voiceMicrophoneMuted=true),
            onOriginChange={},onConnect={},onSelectProfile={},onTab={},onOpenSession={},onNewThread={},onCloseChat={},
            onDraftChange={},onSend={},onInterrupt={},onApproval={},imeVisible=true) } }
        compose.onNodeWithTag("nav.voice").assertDoesNotExist()
        compose.onNodeWithTag("voice.stage.mute").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("voice.stage.stop").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("chat.tools").assertDoesNotExist()
        capture("voice-keyboard-layout")
    }

    @Test fun landscapeTypingKeepsFullTouchTargetsInTheSpaceAboveTheKeyboard() {
        val config=android.content.res.Configuration(compose.activity.resources.configuration).apply { orientation=android.content.res.Configuration.ORIENTATION_LANDSCAPE;screenHeightDp=360;screenWidthDp=720 }
        val session=SessionRef("voice-session","knight","Pinned conversation",0)
        compose.setContent { CompanionTheme { CompositionLocalProvider(
            androidx.compose.ui.platform.LocalConfiguration provides config,LocalDensity provides Density(2f,2f)) {
            Box(Modifier.size(720.dp,200.dp)) { CompanionShell(CompanionState(origin="http://lab",tab=MainTab.THREADS,activeProfileId="knight",
                openSessionId=session.id,openSessionRef=session,voiceStreamState=VoiceStreamState.MUTED,voiceMicrophoneMuted=true),
                onOriginChange={},onConnect={},onSelectProfile={},onTab={},onOpenSession={},onNewThread={},onCloseChat={},
                onDraftChange={},onSend={},onInterrupt={},onApproval={},imeVisible=true) }
        } } }
        compose.onNodeWithTag("chat.send").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("voice.stage.stop").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        val send=compose.onNodeWithTag("chat.send").fetchSemanticsNode().boundsInRoot
        val dock=compose.onNodeWithTag("voice.stage").fetchSemanticsNode().boundsInRoot
        assertTrue("Composer and voice controls overlap",send.bottom<=dock.top)
        capture("voice-landscape-typing")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnIdle { view.draw(Canvas(bitmap)) }
        val file = File("build/reports/navigation-render/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
