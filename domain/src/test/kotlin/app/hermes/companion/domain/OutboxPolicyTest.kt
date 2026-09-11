package app.hermes.companion.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxPolicyTest {
    @Test
    fun http409AndRpc4009AreBusy() {
        assertEquals(SendFate.BUSY, OutboxPolicy.classify("http_409", "conflict"))
        assertEquals(SendFate.BUSY, OutboxPolicy.classify("rpc_4009", "session busy"))
        assertEquals(SendFate.BUSY, OutboxPolicy.classify("ws_4009", "busy"))
        assertTrue(OutboxPolicy.isBusy("rpc_4009", "session busy"))
    }

    @Test
    fun socketDropIsOffline() {
        assertEquals(SendFate.OFFLINE, OutboxPolicy.classify("ws_closed", "websocket closed"))
        assertEquals(SendFate.OFFLINE, OutboxPolicy.classify("ws_failed", "failed to connect"))
        assertEquals(SendFate.OFFLINE, OutboxPolicy.classify("", "SocketTimeoutException"))
        assertEquals(SendFate.OFFLINE, OutboxPolicy.classify("http_502", "bad gateway"))
    }

    @Test
    fun validationIsFailed() {
        assertEquals(SendFate.FAILED, OutboxPolicy.classify("rpc_4002", "text is required"))
        assertEquals(SendFate.FAILED, OutboxPolicy.classify("http_400", "bad request"))
    }

    @Test
    fun backoffGrowsThenCaps() {
        assertEquals(1_000L, OutboxPolicy.backoffMs(0))
        assertEquals(2_000L, OutboxPolicy.backoffMs(1))
        assertEquals(30_000L, OutboxPolicy.backoffMs(8))
        assertFalse(OutboxPolicy.giveUp(4))
        assertTrue(OutboxPolicy.giveUp(5))
    }
}

class OutboxReconcileTest {
    @Test
    fun firstAttemptsAreNeverSuppressed() {
        // A fresh send must go out even if the same text exists on the host already, otherwise
        // repeating yourself silently loses the message.
        val landed = OutboxPolicy.landedIds(
            pending = listOf(Triple("u-1", "ok", 0)),
            serverUserTexts = listOf("ok"),
        )
        assertTrue(landed.isEmpty())
    }

    @Test
    fun retriedItemWhoseEchoIsPresentCountsAsLanded() {
        val landed = OutboxPolicy.landedIds(
            pending = listOf(Triple("u-1", "deploy the thing", 1)),
            serverUserTexts = listOf("hello", "deploy the thing"),
        )
        assertEquals(setOf("u-1"), landed)
    }

    @Test
    fun retriedItemWithNoEchoIsStillPending() {
        val landed = OutboxPolicy.landedIds(
            pending = listOf(Triple("u-1", "never arrived", 2)),
            serverUserTexts = listOf("something else"),
        )
        assertTrue(landed.isEmpty())
    }

    @Test
    fun twoIdenticalPendingsNeedTwoEchoes() {
        val one = OutboxPolicy.landedIds(
            pending = listOf(Triple("u-1", "ping", 1), Triple("u-2", "ping", 1)),
            serverUserTexts = listOf("ping"),
        )
        assertEquals(setOf("u-1"), one)
        val two = OutboxPolicy.landedIds(
            pending = listOf(Triple("u-1", "ping", 1), Triple("u-2", "ping", 1)),
            serverUserTexts = listOf("ping", "ping"),
        )
        assertEquals(setOf("u-1", "u-2"), two)
    }

    @Test
    fun onlyTheTailIsSearchedSoOldIdenticalTextDoesNotMatch() {
        val ancient = List(OutboxPolicy.RECONCILE_TAIL + 5) { "ok" } + List(OutboxPolicy.RECONCILE_TAIL) { "filler" }
        val landed = OutboxPolicy.landedIds(
            pending = listOf(Triple("u-1", "ok", 1)),
            serverUserTexts = ancient,
        )
        assertTrue("an 'ok' beyond the window must not count as this retry's echo", landed.isEmpty())
    }

    @Test
    fun whitespaceAndEmptyTextAreHandled() {
        assertEquals(
            setOf("u-1"),
            OutboxPolicy.landedIds(listOf(Triple("u-1", "  hi  ", 1)), listOf("hi")),
        )
        assertTrue(OutboxPolicy.landedIds(listOf(Triple("u-1", "   ", 1)), listOf("")).isEmpty())
    }

    @Test
    fun exhaustedMatchesGiveUpSoOfflineItemsStopBlocking() {
        assertTrue(OutboxPolicy.exhausted(OutboxPolicy.MAX_ATTEMPTS))
        assertTrue(!OutboxPolicy.exhausted(OutboxPolicy.MAX_ATTEMPTS - 1))
    }

    @Test
    fun aDroppedStreamClassifiesAsOfflineNotFailed() {
        // This is the classification that makes a mid-turn drop retryable — and, before the
        // queue fix, the one that wedged the outbox.
        assertEquals(SendFate.OFFLINE, OutboxPolicy.classify("ws_closed", "socket closed"))
        assertEquals(SendFate.OFFLINE, OutboxPolicy.classify("", "SocketTimeoutException"))
    }
}

class OutboxCancelledTurnTest {
    @Test
    fun aCancelledFirstSendIsCheckedNotAssumedLost() {
        // Exiting the chat window or interrupting cuts the stream *after* the turn was
        // submitted. Treating that as "never sent" is what made the message come back and
        // re-queue on every re-entry.
        val pending = listOf(Triple("u-1", "what is the status", 0))
        val server = listOf("what is the status")

        // Default gate: a blind first send is not second-guessed.
        assertTrue(OutboxPolicy.landedIds(pending, server).isEmpty())
        // Cancellation path: we know it was submitted, so the transcript is authoritative.
        assertEquals(
            setOf("u-1"),
            OutboxPolicy.landedIds(pending, server, requireAttempt = false),
        )
    }

    @Test
    fun aCancelledSendThatNeverArrivedStaysQueued() {
        val landed = OutboxPolicy.landedIds(
            pending = listOf(Triple("u-1", "never got there", 0)),
            serverUserTexts = listOf("something unrelated"),
            requireAttempt = false,
        )
        assertTrue("must stay queued so the user does not silently lose it", landed.isEmpty())
    }

    @Test
    fun requireAttemptFalseStillRespectsOneForOneAndTheTailWindow() {
        assertEquals(
            setOf("u-1"),
            OutboxPolicy.landedIds(
                listOf(Triple("u-1", "hi", 0), Triple("u-2", "hi", 0)),
                listOf("hi"),
                requireAttempt = false,
            ),
        )
        val far = List(OutboxPolicy.RECONCILE_TAIL + 3) { "hi" } + List(OutboxPolicy.RECONCILE_TAIL) { "x" }
        assertTrue(
            OutboxPolicy.landedIds(listOf(Triple("u-1", "hi", 0)), far, requireAttempt = false).isEmpty(),
        )
    }
}
