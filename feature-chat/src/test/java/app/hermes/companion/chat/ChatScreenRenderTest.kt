package app.hermes.companion.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.MessageDelivery
import app.hermes.companion.model.RoomRef
import androidx.compose.ui.test.assertTextEquals
import app.hermes.companion.model.RoomParticipant
import androidx.compose.ui.test.onAllNodesWithText
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the transcript in its streaming states and checks role/stream markers are present.
 * Also writes PNG captures to build/reports/chat-render/ for eyeballing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h720dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatScreenRenderTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val user = ChatMessage(id = "u1", role = MessageRole.USER, text = "the ws dies after 30s on gated dashboards")
    private val toolDone = ChatMessage(
        id = "t1", role = MessageRole.TOOL, text = "journalctl -u hermes-gateway · 1.2s",
        toolName = "terminal", toolDetail = "journalctl -u hermes-gateway · 1.2s",
    )
    private val toolLive = ChatMessage(
        id = "t2", role = MessageRole.TOOL, text = "echo ok", toolName = "terminal", toolDetail = "echo ok", toolRunning = true,
    )
    private val agentDone = ChatMessage(id = "a1", role = MessageRole.ASSISTANT, text = "ticket is single-use. mint per connect.")
    private val agentLive = ChatMessage(
        id = "a2", role = MessageRole.ASSISTANT, text = "reading the journal now — the gateway restarted twice", streaming = true,
    )

    private fun mount(
        messages: List<ChatMessage>,
        streaming: Boolean,
        participants: List<RoomParticipant> = emptyList(),
        pendingSpeaker: String? = null,
        roomRef: RoomRef? = null,
        draft: String = "",
        submitting: Boolean = false,
        creatingThread: Boolean = false,
        onReadAloud: (ChatMessage) -> Unit = {},
        readAloudMessageId: String? = null,
        approval: app.hermes.companion.model.ApprovalPrompt? = null,
        approvalBusy: Boolean = false,
        onSend: () -> Unit = {},
        onInterrupt: () -> Unit = {},
        onVoice: () -> Unit = {},
        onVoiceLong: (() -> Unit)? = null,
        onAttach: () -> Unit = {},
        onModels: () -> Unit = {},
        onApproval: (String) -> Unit = {},
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompanionTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ChatScreen(
                        messages = messages,
                        draft = draft,
                        streaming = streaming,
                        submitting = submitting,
                        creatingThread = creatingThread,
                        onReadAloud = onReadAloud,
                        readAloudMessageId = readAloudMessageId,
                        error = null,
                        approval = approval,
                        approvalBusy = approvalBusy,
                        onDraftChange = {},
                        onSend = onSend,
                        onInterrupt = onInterrupt,
                        onVoiceClick = onVoice,
                        onVoiceLongClick = onVoiceLong,
                        onToggleAttach = onAttach,
                        onOpenModelPicker = onModels,
                        onApproval = onApproval,
                        threadId = "s1",
                        participants = participants,
                        pendingSpeaker = pendingSpeaker,
                        room = roomRef,
                        roomCandidates = if (roomRef != null) listOf("knight" to "knight") else emptyList(),
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(600)
    }

    private val room = listOf(RoomParticipant("coder", "COD"), RoomParticipant("ops", "OPS"), RoomParticipant("ash", "ASH"))

    @Test fun pendingDecisionShowsSubmissionAndDisablesRepeatedActions() {
        val choices = mutableListOf<String>()
        mount(emptyList(), streaming = false, draft = "**preserved**", approvalBusy = true,
            approval = app.hermes.companion.model.ApprovalPrompt("fixture", command = "Synthetic"), onApproval = choices::add)
        rule.onNodeWithTag("chat.approval.submitting").assertExists()
        rule.onNodeWithTag("chat.approval.allow").assertIsNotEnabled()
        rule.onNodeWithTag("chat.approval.deny").assertIsNotEnabled()
        assertTrue(choices.isEmpty())
    }

    @Test fun readAloudTargetsTheCompletedMessageAndNeverAnInFlightReply() {
        val selected = mutableListOf<ChatMessage>()
        mount(listOf(agentDone, agentLive), streaming = true, onReadAloud = selected::add)
        rule.onNodeWithTag("chat.read.a2").assertDoesNotExist()
        rule.onNodeWithTag("chat.read.a1").performClick()
        assertTrue(selected == listOf(agentDone))
    }
    @Test fun playingReadAloudOffersStopWithoutHidingRichTextFormatting() {
        mount(listOf(agentDone), streaming = false, readAloudMessageId = agentDone.id)
        rule.onNodeWithContentDescription("Stop reading aloud").assertIsDisplayed()
        rule.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
    }

    @Test fun failedInputOffersRetryAndDoesNotPretendItWasSent() {
        mount(listOf(user.copy(delivery = MessageDelivery.FAILED)), streaming = false)
        rule.onNodeWithText("failed").assertIsDisplayed()
        rule.onNodeWithText("RETRY").assertIsDisplayed()
    }

    @Test fun anUncertainInputOffersCheckRatherThanAnAutomaticResend() {
        mount(listOf(user.copy(delivery = MessageDelivery.UNCERTAIN)), streaming = false)
        rule.onNodeWithText("checking delivery").assertIsDisplayed()
        rule.onNodeWithText("CHECK").assertIsDisplayed()
    }

    @Test fun aDraftDuringGenerationSteersTheActiveTurn() {
        mount(listOf(user, agentLive), streaming = true, draft = "use the other directory")
        rule.onNodeWithContentDescription("Steer reply").assertIsDisplayed()
    }

    @Test fun sendingAndCreationShowTheirOwnComposerState() {
        mount(listOf(user), streaming = false, submitting = true)
        rule.onNodeWithContentDescription("Sending message").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun restoredComposerKeepsRichTextControlsVisible() {
        mount(emptyList(), streaming = false, draft = "A readable message")
        for (tag in listOf("bold", "italic", "code", "list")) {
            rule.onNodeWithTag("chat.fmt.$tag").assertIsDisplayed()
        }
        rule.onNodeWithTag("chat.slash").assertIsDisplayed()
        rule.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
        rule.onNodeWithContentDescription("Send message").assertIsDisplayed()
        snap("13-restored-rich-text-composer")
    }

    @Test fun expandedEditorPreservesRichTextAndNeverSubmitsTheDraft() {
        var draft by mutableStateOf("Keep **formatting**")
        var sends = 0
        rule.setContent { CompanionTheme { ChatScreen(emptyList(), draft, false, null, null,
            { draft = it }, { sends++ }, {}, {}, threadId = "expanded-fixture") } }
        rule.onNodeWithTag("chat.expand").performClick()
        rule.onNodeWithTag("chat.expanded.draft").performTextInput(" and notes")
        rule.onNodeWithTag("chat.expanded.done").performScrollTo().performClick()
        assertTrue(draft.contains("**formatting**") && draft.contains("and notes"))
        org.junit.Assert.assertEquals(0, sends)
        rule.onNodeWithTag("chat.draft").assertTextEquals(draft)
    }

    @Test fun composerControlsStackBesideAWideDraftAndDispatchTheirOwnActions() {
        val actions = mutableListOf<String>()
        mount(emptyList(), streaming = false, onSend = { actions += "send" },
            onVoice = { actions += "voice" }, onAttach = { actions += "attach" })
        fun bounds(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val attach = bounds("chat.attach")
        val slash = bounds("chat.slash")
        val voice = bounds("chat.voice")
        val send = bounds("chat.send")
        val draft = bounds("chat.draft")
        assertTrue(attach.left == slash.left && attach.bottom < slash.top)
        assertTrue(voice.left == send.left && voice.bottom < send.top)
        assertTrue(draft.left >= attach.right && draft.right <= send.left)
        assertTrue(draft.width > attach.width * 3)
        rule.onNodeWithTag("chat.attach").performClick()
        rule.onNodeWithTag("chat.voice").performClick()
        rule.onNodeWithTag("chat.send").performClick()
        assertTrue(actions == listOf("attach", "voice", "send"))
        snap("14-vertical-composer-controls")
    }

    @Test fun stopIconInterruptsInsteadOfSendingAnEmptySteer() {
        val actions = mutableListOf<String>()
        mount(listOf(agentLive), streaming = true, onSend = { actions += "send" }, onInterrupt = { actions += "stop" })
        rule.onNodeWithContentDescription("Interrupt reply").performClick()
        assertTrue(actions == listOf("stop"))
    }

    @Test fun modelSettingsAreVisibleWithoutFocusAndKeepAllFormattingAvailable() {
        var opened = 0
        mount(emptyList(), streaming = false, draft = "Keep **this** draft", onModels = { opened++ })
        rule.onNodeWithText("Model").assertIsDisplayed()
        rule.onNodeWithText("Reasoning").assertIsDisplayed()
        rule.onNodeWithTag("chat.model.settings").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        for (tag in listOf("bold", "italic", "code", "list")) rule.onNodeWithTag("chat.fmt.$tag").assertIsDisplayed()
        assertTrue(opened == 1)
    }

    @Test fun compactModelControlsStayBesideTheFieldAtDoubleText() {
        var opened = 0
        rule.setContent {
            CompanionTheme {
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(2f, 2f)) {
                    Box(Modifier.size(720.dp, 128.dp)) {
                        MarkdownComposer("Preserve **this** draft", {}, compactLayout = true,
                            onOpenModelSettings = { opened++ }, onOpenReasoningSettings = { opened += 10 })
                    }
                }
            }
        }
        rule.onNodeWithTag("chat.model.settings").assertIsDisplayed().assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("chat.reasoning.settings").assertIsDisplayed().assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(opened == 11)
        rule.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
        rule.onNodeWithTag("chat.draft").assertIsDisplayed()
    }

    @Test
    fun roomBarShowsFloorStateAndActions() {
        val live = RoomRef(id = "r-1", title = "triage", participants = room, state = "running", turnsUsed = 3, budget = 12, speaking = "ops", hands = "coder")
        mount(emptyList(), streaming = true, participants = room, pendingSpeaker = "ops", roomRef = live)
        rule.onNodeWithTag("room.bar").assertIsDisplayed()
        rule.onNodeWithText("R 3/12").assertIsDisplayed()
        rule.onNodeWithTag("room.pause").assertIsDisplayed()
        rule.onNodeWithTag("room.stop").assertIsDisplayed()
        rule.onNodeWithTag("room.add").assertIsDisplayed()
        rule.onAllNodesWithTag("room.floor").assertCountEquals(0)
        snap("06-room-bar-live")
    }

    @Test
    fun pausedRoomOffersContinueUnderTheTranscript() {
        val paused = RoomRef(id = "r-1", title = "triage", participants = room, state = "paused", pauseReason = "budget", turnsUsed = 12, budget = 12, summary = "TTL is the cause")
        mount(emptyList(), streaming = false, participants = room, roomRef = paused)
        rule.onNodeWithText("PAUSED · budget").assertIsDisplayed()
        rule.onNodeWithTag("room.continue").assertIsDisplayed()
        rule.onNodeWithTag("room.summarize").assertIsDisplayed()
        rule.onNodeWithTag("room.summary.toggle").assertIsDisplayed()
        rule.onNodeWithTag("room.floor").assertIsDisplayed()
        rule.onNodeWithText("paused · budget of 12 turns reached").assertIsDisplayed()
        rule.onNodeWithTag("room.floor.continue").assertIsDisplayed()
        snap("07-room-paused")
    }

    @Test
    fun roomModeLabelsSpeakersAndOffersMentions() {
        val rows = listOf(
            ChatMessage(id = "rm-1", role = MessageRole.USER, text = "gateway restarted twice, who owns it?"),
            ChatMessage(id = "rm-2-t0", role = MessageRole.TOOL, text = "journalctl -u gw", toolName = "terminal", toolDetail = "journalctl -u gw", speaker = "coder"),
            ChatMessage(id = "rm-2", role = MessageRole.ASSISTANT, text = "Ticket mint fails after 30s. @OPS can you pull the journal?", speaker = "coder"),
            ChatMessage(id = "rm-3", role = MessageRole.ASSISTANT, text = "", speaker = "ash", passed = true),
            ChatMessage(id = "a-t4", role = MessageRole.ASSISTANT, text = "Journal shows two restarts at 02:14 and 02:31 —", speaker = "ops", streaming = true),
        )
        mount(rows, streaming = true, participants = room, pendingSpeaker = "ops")
        rule.onNodeWithText("coder").assertIsDisplayed()
        rule.onNodeWithText("ops").assertIsDisplayed()
        rule.onNodeWithTag("chat.passed").assertIsDisplayed()
        rule.onNodeWithText("passed").assertIsDisplayed()
        rule.onNodeWithTag("chat.cursor").assertIsDisplayed()
        rule.onNodeWithTag("chat.mentions").assertIsDisplayed()
        rule.onNodeWithTag("chat.mention.OPS").assertIsDisplayed()
        // v2: the operator is never locked out of a room; SEND stays, STOP lives in the room bar.
        rule.onNodeWithContentDescription("Send message").assertIsDisplayed()
        rule.onAllNodesWithText("INTERRUPT ALL").assertCountEquals(0)
        rule.onAllNodesWithTag("chat.attach").assertCountEquals(0)
        rule.onAllNodesWithText("HERMES").assertCountEquals(0)
        snap("05-room-streaming")
    }

    @Test
    fun roomWaitingShowsSpeakerPending() {
        val rows = listOf(ChatMessage(id = "rm-1", role = MessageRole.USER, text = "@COD status?"))
        mount(rows, streaming = true, participants = room, pendingSpeaker = "coder")
        rule.onNodeWithTag("chat.pending").assertIsDisplayed()
        rule.onNodeWithText("thinking").assertIsDisplayed()
        rule.onNodeWithText("coder").assertIsDisplayed()
        snap("06-room-thinking")
    }

    @Test fun microphoneTapDictatesAndLongPressStartsVoiceWithoutADuplicateTap() {
        var taps=0; var holds=0
        mount(emptyList(),false,onVoice={taps++},onVoiceLong={holds++})
        rule.onNodeWithTag("chat.voice").performClick()
        rule.onNodeWithTag("chat.voice").performTouchInput { longClick() }
        org.junit.Assert.assertEquals(1,taps)
        org.junit.Assert.assertEquals(1,holds)
        rule.onNodeWithTag("chat.fmt.bold").assertIsDisplayed()
    }

    /** Software-draws the decor view; Compose's idle wait never settles with infinite blink animations. */
    private fun snap(name: String) {
        runCatching {
            rule.mainClock.advanceTimeByFrame()
            var bmp: Bitmap? = null
            rule.activityRule.scenario.onActivity { activity ->
                val v = activity.window.decorView
                if (v.width > 0 && v.height > 0) {
                    bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
                }
            }
            val out = bmp ?: error("decor view not laid out")
            val dir = File("build/reports/chat-render").apply { mkdirs() }
            File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("capture skipped for $name: ${it.message}") }
    }

    @Test
    fun idleTranscriptSeparatesRoles() {
        mount(listOf(user, toolDone, agentDone), streaming = false)
        rule.onNodeWithTag("chat.user").assertIsDisplayed()
        rule.onNodeWithTag("chat.assistant").assertIsDisplayed()
        rule.onNodeWithTag("chat.tool").assertIsDisplayed()
        rule.onNodeWithText("You").assertIsDisplayed()
        rule.onNodeWithText("Hermes").assertIsDisplayed()
        rule.onAllNodesWithTag("chat.cursor").assertCountEquals(0)
        rule.onAllNodesWithTag("chat.pending").assertCountEquals(0)
        rule.onNodeWithContentDescription("Dictate message").assertIsDisplayed()
        snap("01-idle")
    }

    @Test
    fun waitingOnToolShowsPendingRow() {
        mount(listOf(user, toolLive), streaming = true)
        rule.onNodeWithTag("chat.tool.running").assertIsDisplayed()
        rule.onNodeWithTag("chat.pending").assertIsDisplayed()
        rule.onNodeWithText("running · terminal").assertIsDisplayed()
        rule.onNodeWithContentDescription("Interrupt reply").assertIsDisplayed()
        snap("02-tool-running")
    }

    @Test
    fun streamingAgentShowsCursorAndLiveLabel() {
        mount(listOf(user, toolDone, agentLive), streaming = true)
        rule.onNodeWithTag("chat.cursor").assertIsDisplayed()
        rule.onNodeWithTag("chat.streaming").assertIsDisplayed()
        rule.onAllNodesWithTag("chat.pending").assertCountEquals(0)
        snap("03-streaming")
    }

    @Test
    fun thinkingBeforeFirstToken() {
        mount(listOf(user), streaming = true)
        rule.onNodeWithTag("chat.pending").assertIsDisplayed()
        rule.onNodeWithText("thinking").assertIsDisplayed()
        snap("04-thinking")
    }
}
