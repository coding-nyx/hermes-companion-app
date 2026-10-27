package app.hermes.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.*
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(sdk = [31], application = android.app.Application::class, qualifiers = "w360dp-h720dp-night-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TranscriptDesignRenderTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()
    private val owner = BotOwner("https://work/hermes", "research")
    private val bot = NativeBot(owner, "Research specialist")
    private val session = SessionRef("s", "research", "Bot Chat", 0)
    private val base = CompanionState(origin = owner.origin, hostName = "Work", activeProfileId = "research", tab = MainTab.THREADS,
        profiles = listOf(ProfileRef("research", "Hermes", "RES")), botMode = BotModeState(owner = owner, bots = listOf(bot)),
        openSessionId = "s", openSessionRef = session)
    private val rows = listOf(
        ChatMessage("u", MessageRole.USER, "Check the plan."),
        ChatMessage("r", MessageRole.ASSISTANT, "Reviewing the request", reasoning = true),
        ChatMessage("t", MessageRole.TOOL, "Ready", toolName = "terminal", toolDetail = "Checked the source"),
        ChatMessage("a", MessageRole.ASSISTANT, "## Plan\nFirst paragraph.\n\nSecond paragraph.\n\n- First detail\n- Second detail\n1. First step\n2. Second step\n> First quoted line\n> Second quoted line"),
    )
    private fun mount(state: CompanionState) {
        compose.mainClock.autoAdvance = false
        compose.setContent { CompanionTheme {
            CompanionShell(state, onOriginChange = {}, onConnect = {}, onSelectProfile = {}, onTab = {}, onOpenSession = {}, onNewThread = {},
                onCloseChat = {}, onDraftChange = {}, onSend = {}, onInterrupt = {}, onApproval = {}, imeVisible = false)
        } }
        compose.mainClock.advanceTimeBy(600)
    }
    private fun checkChat(name: String, large: Boolean = false) {
        mount(base.copy(messages = rows))
        compose.onNodeWithTag("header.session.actions").assertTextContains(bot.title)
        compose.onNodeWithTag("chat.speaker.a", useUnmergedTree = true).assertTextEquals(bot.title)
        compose.onAllNodesWithText("Hermes", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("chat.copy.a").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("chat.read.a").assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        if (!large) {
            val first = compose.onNodeWithText("First detail", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val second = compose.onNodeWithText("Second detail", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val paragraph = compose.onNodeWithText("First paragraph.", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val nextParagraph = compose.onNodeWithText("Second paragraph.", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("Consecutive list lines must be tighter than paragraph breaks", second.top - first.bottom < nextParagraph.top - paragraph.bottom)
        }
        capture("$name-bottom")
        compose.onNodeWithTag("chat.list").performScrollToIndex(0)
        capture("$name-top")
    }
    @Test fun mixedTranscriptDark() = checkChat("chat-dark")
    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xhdpi") fun mixedTranscriptLight() = checkChat("chat-light")
    @Test fun mixedTranscriptLarge() { org.robolectric.RuntimeEnvironment.setFontScale(2f); checkChat("chat-large", true) }

    private fun richBlocks(name: String) {
        mount(base.copy(messages = listOf(ChatMessage("rich", MessageRole.ASSISTANT,
            "## Source check\nA wrapped paragraph stays readable alongside lists, code and tables.\n\n```kotlin\nval ready = true\nprintln(ready)\n```\n\n| Check | Result |\n| --- | --- |\n| Names | Passed |\n| Spacing | Passed |\n\n- Keep the compact rhythm\n- Preserve readable text"))))
        capture("$name-bottom")
        compose.onNodeWithTag("chat.list").performScrollToIndex(0)
        capture("$name-top")
    }
    @Test fun codeAndTableDark() = richBlocks("rich-dark")
    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xhdpi") fun codeAndTableLight() = richBlocks("rich-light")
    @Test fun codeAndTableLarge() { org.robolectric.RuntimeEnvironment.setFontScale(2f); richBlocks("rich-large") }

    private fun checkGroup(name: String) {
        val group = NativeGroup("g", "Launch team", listOf(NativeGroupMember("seat", "research", "research", "Hermes"), NativeGroupMember("build", "coder", "coder", "Hermes")), "host", 1)
        mount(base.copy(tab = MainTab.BOTS, openSessionId = null, openSessionRef = null,
            botMode = base.botMode.copy(bots = listOf(bot, NativeBot(owner.copy(profile = "coder"), "Build engineer")), selected = NativeGroupSnapshot(group), groupVerified = true, events = listOf(
                NativeGroupEvent("e", 1, "message.member", "Hermes", "## Findings\n- First detail\n- Second detail\n\nThe checks passed.", "thread", actorId = "seat"),
                NativeGroupEvent("e2", 2, "message.member", "Hermes", "Ready to implement.", actorId = "build"),
            ))))
        compose.onNodeWithTag("bots.event.speaker.1", useUnmergedTree = true).assertTextEquals(bot.title)
        compose.onNodeWithTag("bots.event.speaker.2", useUnmergedTree = true).assertTextEquals("Build engineer")
        compose.onNodeWithContentDescription("Reply in thread").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithText("Hermes", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag("navigation.bar").assertDoesNotExist()
        capture(name)
    }
    @Test fun groupSpeakerDark() = checkGroup("group-dark")
    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xhdpi") fun groupSpeakerLight() = checkGroup("group-light")
    @Test fun groupSpeakerLarge() { org.robolectric.RuntimeEnvironment.setFontScale(2f); checkGroup("group-large") }
    @Test fun pendingAndCompletedRepliesUseTheSameAgentName() {
        mount(base.copy(messages = listOf(rows.last().copy(streaming = false)), streaming = true))
        compose.onAllNodesWithText(bot.title, useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithText("HERMES", useUnmergedTree = true).assertCountEquals(0)
    }
    @Test fun ordinaryAgentReplyUsesItsProfileNameWithoutBotState() {
        mount(base.copy(botMode = BotModeState(), profiles = listOf(ProfileRef("research", "Research agent", "RES")),
            openSessionRef = session.copy(title = "Release plan"), messages = listOf(rows.last())))
        compose.onNodeWithTag("chat.speaker.a", useUnmergedTree = true).assertTextEquals("Research agent")
    }
    private fun roomNames(name: String) {
        val room = RoomRef("room", "Launch room", participants = listOf(RoomParticipant("research", "RES"), RoomParticipant("ops", "OPS")))
        mount(base.copy(openSessionId = null, openSessionRef = null, openRoomId = room.id, openRoom = room, messages = listOf(
            ChatMessage("known", MessageRole.ASSISTANT, "Research result", speaker = "research"),
            ChatMessage("unknown", MessageRole.ASSISTANT, "Room summary"))))
        compose.onNodeWithTag("chat.speaker.known", useUnmergedTree = true).assertTextEquals(bot.title)
        compose.onNodeWithTag("chat.speaker.unknown", useUnmergedTree = true).assertTextEquals(room.title)
        capture(name)
    }
    @Test fun unknownRoomSpeakerUsesTheRoomNameAndKnownSpeakerUsesItsBotName() = roomNames("room-dark")
    @Test @Config(qualifiers = "w360dp-h720dp-notnight-xhdpi") fun roomNamesLight() = roomNames("room-light")
    @Test fun roomNamesLarge() { org.robolectric.RuntimeEnvironment.setFontScale(2f); roomNames("room-large") }
    private fun capture(name: String) {
        compose.waitForIdle()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File("/tmp/hermes-transcript-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
