package app.hermes.desk

import android.Manifest
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.*
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/** No host credentials, real prompts or microphone recording are used by this smoke test. */
class DeskDeviceTest {
    @get:Rule val rule = createAndroidComposeRule<DeskActivity>()
    @Test fun freshInstallOpensConnectionAndSettingsWithoutMicrophoneOrAwakeFlag() {
        rule.onNodeWithText("Connect to Hermes").assertIsDisplayed()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Reduce motion").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        rule.onNodeWithText("Connect to Hermes").assertIsDisplayed()
        assertEquals(PackageManager.PERMISSION_DENIED, ContextCompat.checkSelfPermission(rule.activity, Manifest.permission.RECORD_AUDIO))
        assertEquals(0, rule.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    @Test fun nativeHermesFixtureConnectsResumesAndStreamsExactlyOneTextTurn() {
        val prompts = CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty().substringBefore('?')
                    return when (path) {
                        "/api/status" -> json("""{"auth_required":false,"gateway_running":true}""")
                        "/" -> MockResponse().setBody("<html></html>")
                        "/api/profiles" -> json("""{"profiles":[{"id":"default","display_name":"Fixture"}]}""")
                        "/api/sessions" -> json("""{"sessions":[{"id":"fixture-session","profile":"default","title":"Desk smoke fixture"}]}""")
                        "/api/sessions/fixture-session/messages" -> json("""{"messages":[]}""")
                        "/api/sessions/fixture-session/approval" -> MockResponse().setResponseCode(404)
                        "/api/ws" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: Response) {
                                webSocket.send("""{"method":"event","params":{"type":"gateway.ready","payload":{"instance_id":"desk-device-fixture"}}}""")
                            }
                            override fun onMessage(webSocket: WebSocket, text: String) {
                                val rpc = JSONObject(text)
                                val method = rpc.getString("method")
                                val result = when (method) {
                                    "session.list" -> JSONObject("""{"sessions":[{"session_id":"fixture-session","stored_session_id":"fixture-session","profile":"default","title":"Desk smoke fixture"}]}""")
                                    "session.resume" -> JSONObject("""{"session_id":"fixture-session","stored_session_id":"fixture-session","running":false}""")
                                    "session.history" -> JSONObject("""{"messages":[]}""")
                                    "prompt.submit" -> {
                                        val params = rpc.getJSONObject("params")
                                        prompts += params.getString("text")
                                        assertEquals("fixture-session", params.getString("session_id"))
                                        JSONObject("""{"status":"streaming","user_row_id":1}""")
                                    }
                                    else -> JSONObject()
                                }
                                webSocket.send(JSONObject().put("jsonrpc", "2.0").put("id", rpc.get("id")).put("result", result).toString())
                                if (method == "prompt.submit") {
                                    webSocket.send("""{"method":"event","params":{"type":"assistant.delta","session_id":"fixture-session","payload":{"text":"Fixture response"}}}""")
                                    webSocket.send("""{"method":"event","params":{"type":"message.complete","session_id":"fixture-session","payload":{"text":"Fixture response","status":"complete"}}}""")
                                }
                            }
                        })
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
            server.start()
            rule.onNodeWithText("Dashboard address").performTextClearance()
            rule.onNodeWithText("Dashboard address").performTextInput(server.url("/").toString().trimEnd('/'))
            rule.onNodeWithText("Connect to Hermes").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("History").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("History").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Choose conversation").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("Choose conversation").performClick()
            rule.onNodeWithText("Desk smoke fixture").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Start voice").fetchSemanticsNodes().any { it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() } }
            rule.onNodeWithText("Say what’s on your mind").performTextInput("Synthetic device test")
            rule.onNodeWithText("Send").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithText("Fixture response").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(listOf("Synthetic device test"), prompts.toList())
            rule.onNodeWithText("Close history").performClick()
            rule.onNodeWithText("MIC OFF").assertIsDisplayed()
            rule.onNodeWithText("Settings").performClick()
            rule.onNodeWithText("Disconnect / change host").performScrollTo().performClick()
            rule.onNodeWithText("Connect to Hermes").assertIsDisplayed()
        }
    }
}
