package app.hermes.companion.data.remote

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NativeHistoryPagingTest {
    @Test fun nativeOffsetPagesDoNotRequireTheAnchorToAppearInTheResponse() = runBlocking {
        MockWebServer().use { server ->
            fun page(from: Int, to: Int, offset: Int) = MockResponse().setBody(
                """{"messages":[${(from..to).joinToString(",") { """{"row_id":$it,"role":"user","content":"message $it"}""" }}],"pagination":{"limit":80,"offset":$offset,"order":"latest"}}""")
            server.enqueue(page(81, 160, 0))
            server.enqueue(page(1, 80, 80))
            server.enqueue(MockResponse().setBody("""{"messages":[],"pagination":{"limit":80,"offset":160,"order":"latest"}}"""))
            val client = DashboardClient()
            val origin = server.url("/").toString().trimEnd('/')
            val initial = client.pageMessages(origin, "s", "coder")
            val older = client.pageMessages(origin, "s", "coder", beforeId = initial.messages.first().id)
            val empty = client.pageMessages(origin, "s", "coder", beforeId = older.messages.first().id)
            assertEquals("message 81", initial.messages.first().text)
            assertEquals("message 1", older.messages.first().text)
            assertTrue(older.hasMore)
            assertFalse(empty.hasMore)
            server.takeRequest()
            val second = server.takeRequest()
            assertEquals("80", second.requestUrl!!.queryParameter("offset"))
            assertNull(second.requestUrl!!.queryParameter("before"))
            assertEquals("coder", second.requestUrl!!.queryParameter("profile"))
            assertEquals("160", server.takeRequest().requestUrl!!.queryParameter("offset"))
        }
    }

    @Test fun finalOnlyResponsesCarryBothTheAnswerAndTheOutcome() {
        val inbound = RpcCodec.parseLine("""{"method":"event","params":{"type":"message.complete","session_id":"s","payload":{"text":"answer","status":"error","error":"provider error"}}}""") as RpcInbound.Event
        val final = RpcCodec.toChatEvent(inbound.event) as app.hermes.companion.model.ChatEvent.Final
        assertEquals("answer", final.text)
        assertEquals("error", final.status)
        assertEquals("provider error", final.error)
    }
}
