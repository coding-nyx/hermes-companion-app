package app.hermes.companion.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionTheme
import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import java.io.File
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

    private fun mount(messages: List<ChatMessage>, streaming: Boolean) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            CompanionTheme {
                Box(Modifier.size(360.dp, 720.dp)) {
                    ChatScreen(
                        messages = messages,
                        draft = "",
                        streaming = streaming,
                        error = null,
                        approval = null,
                        onDraftChange = {},
                        onSend = {},
                        onInterrupt = {},
                        onApproval = {},
                        threadId = "s1",
                    )
                }
            }
        }
        rule.mainClock.advanceTimeBy(600)
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
        rule.onNodeWithText("YOU").assertIsDisplayed()
        rule.onNodeWithText("HERMES").assertIsDisplayed()
        rule.onAllNodesWithTag("chat.cursor").assertCountEquals(0)
        rule.onAllNodesWithTag("chat.pending").assertCountEquals(0)
        rule.onNodeWithText("SEND").assertIsDisplayed()
        snap("01-idle")
    }

    @Test
    fun waitingOnToolShowsPendingRow() {
        mount(listOf(user, toolLive), streaming = true)
        rule.onNodeWithTag("chat.tool.running").assertIsDisplayed()
        rule.onNodeWithTag("chat.pending").assertIsDisplayed()
        rule.onNodeWithText("running · terminal").assertIsDisplayed()
        rule.onNodeWithText("INTERRUPT").assertIsDisplayed()
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
