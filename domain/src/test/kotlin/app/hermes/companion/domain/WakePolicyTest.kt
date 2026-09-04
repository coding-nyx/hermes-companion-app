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
        assertEquals("sess-1" to "coder", WakePolicy.parseDeepLink(link))
        assertNull(WakePolicy.parseDeepLink("https://evil.example/open?session=s&profile=p"))
    }
}
