package app.hermes.companion.voice

import app.hermes.companion.model.NativeLiveEvent
import org.junit.Assert.*
import org.junit.Test

class LiveConversationLedgerTest {
    @Test fun delayedFragmentsAreAssembledInSpokenOrderBeforeTheDelegationOffset() {
        val ledger = LiveConversationLedger()
        ledger.caption(NativeLiveEvent.Caption("user", "not Friday.", 500, 1000, "later"))
        ledger.caption(NativeLiveEvent.Caption("assistant", "A future answer.", 2000, 2500, "future"))
        ledger.caption(NativeLiveEvent.Caption("user", "Thursday, ", 0, 500, "earlier"))
        val request = requireNotNull(ledger.request(NativeLiveEvent.Delegation("ordered", 1500)))
        assertEquals("Thursday, not Friday.", request.first)
        assertFalse(request.second.contains("future"))
    }
    @Test fun aDelegationNeedsTranscriptContextAndCanAdmitWorkOnlyOnce() {
        val ledger = LiveConversationLedger()
        val delegation = NativeLiveEvent.Delegation("work", 1500)
        assertNull(ledger.request(delegation))
        val first = NativeLiveEvent.Caption("user", "Thursday, ", 0, 500, "c1")
        assertTrue(ledger.caption(first)); assertFalse(ledger.caption(first))
        ledger.caption(NativeLiveEvent.Caption("user", "not Friday.", 500, 1000, "c2"))
        ledger.caption(NativeLiveEvent.Caption("assistant", "I will check.", 1600, 2000, "c3"))
        val request = requireNotNull(ledger.request(delegation))
        assertEquals("Thursday, not Friday.", request.first)
        assertFalse(request.second.contains("I will check."))
        assertNull(ledger.request(delegation))
    }

    @Test fun shortRepliesKeepTheRelevantSpokenConversationWithoutSubmittingCaptions() {
        val ledger = LiveConversationLedger()
        ledger.caption(NativeLiveEvent.Caption("assistant", "Should I search today’s email?", 0, 1000))
        ledger.caption(NativeLiveEvent.Caption("user", "Yes.", 1200, 1500))
        assertEquals(2, ledger.captions.size)
        val request = requireNotNull(ledger.request(NativeLiveEvent.Delegation("yes", 2000)))
        assertEquals("Yes.", request.first)
        assertTrue(request.second.contains("today’s email"))
    }
}
