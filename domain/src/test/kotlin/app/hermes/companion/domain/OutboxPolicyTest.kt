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
