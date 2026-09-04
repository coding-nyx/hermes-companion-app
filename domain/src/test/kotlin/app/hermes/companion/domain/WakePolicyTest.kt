package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePolicyTest {
    @Test
    fun parsesBareAndNtfyWrapped() {
        val bare = WakePolicy.parse("""{"type":"approval.request","session_id":"s1","profile":"coder"}""")!!
        assertEquals("approval.request", bare.type)
        assertEquals("s1", bare.sessionId)
        assertEquals("coder", bare.profile)
        val wrapped = WakePolicy.parse(
            """{"id":"n1","message":"{\"type\":\"clarify\",\"session_id\":\"s2\",\"profile\":\"ops\"}"}""",
        )!!
        assertEquals("clarify", wrapped.type)
        assertEquals("s2", wrapped.sessionId)
    }

    @Test
    fun pingCarriesHostFromPayloadOrTopic() {
        val fromTopic = WakePolicy.parse(
            """{"type":"clarify","session_id":"s","profile":"coder"}""",
            origin = "http://lab:9120",
        )!!
        assertEquals("http://lab:9120", fromTopic.origin)
        val fromPayload = WakePolicy.parse(
            """{"type":"clarify","session_id":"s","profile":"coder","origin":"http://hub:9120"}""",
            origin = "http://lab:9120",
        )!!
        assertEquals("http://hub:9120", fromPayload.origin)
        assertEquals("", WakePolicy.parse("""{"type":"error","session_id":"s","profile":"p"}""")!!.origin)
    }

    @Test
    fun ignoresTranscriptAndUnknownType() {
        assertNull(WakePolicy.parse("""{"type":"chat.delta","session_id":"s","profile":"coder","body":"secret"}"""))
        val ping = WakePolicy.parse(
            """{"type":"error","session_id":"s","profile":"coder","body":"do not keep","content":"nope"}""",
        )!!
        assertEquals("error", ping.type)
        assertEquals("s", ping.sessionId)
    }

    @Test
    fun sseAndDeepLink() {
        assertEquals("https://ntfy.local/hermes/sse", WakePolicy.sseUrl("https://ntfy.local/hermes"))
        assertNull(WakePolicy.sseUrl("ntfy.local/hermes"))
        val link = WakePolicy.deepLink("sess-1", "coder")
        assertEquals("hermes-companion://open?session=sess-1&profile=coder", link)
        assertEquals(DeepLink("sess-1", "coder", ""), WakePolicy.parseDeepLink(link))
        assertNull(WakePolicy.parseDeepLink("https://evil.example/open?session=s&profile=p"))
        val hosted = WakePolicy.deepLink("sess-1", "coder", "http://100.88.4.63:9120")
        assertEquals(
            "hermes-companion://open?session=sess-1&profile=coder&host=http%3A%2F%2F100.88.4.63%3A9120",
            hosted,
        )
        assertEquals(DeepLink("sess-1", "coder", "http://100.88.4.63:9120"), WakePolicy.parseDeepLink(hosted))
    }
}
