package app.hermes.companion.domain

import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.OutboxItem
import org.junit.Assert.*
import org.junit.Test

class DeliveryPolicyTest {
    private fun item(baseline: List<String>? = null) =
        OutboxItem("u", "host", "profile", "session", "yes", 1, baselineUserIds = baseline)

    @Test fun anOlderIdenticalMessageDoesNotAcknowledgeANewSend() {
        assertFalse(DeliveryPolicy.landed(item(listOf("row-4")), listOf(ChatMessage("row-4", MessageRole.USER, "yes"))))
    }

    @Test fun aNewDurableRowAcknowledgesTheSubmission() {
        assertTrue(DeliveryPolicy.landed(item(listOf("row-4")), listOf(ChatMessage("row-4", MessageRole.USER, "yes"), ChatMessage("row-5", MessageRole.USER, "yes"))))
    }

    @Test fun unsentInputsAreNeverSuppressedByHistoricalText() {
        assertFalse(DeliveryPolicy.landed(item(), listOf(ChatMessage("row-4", MessageRole.USER, "yes"))))
    }

    @Test fun aPersistedAttemptWithoutAnAckNeedsReconciliation() {
        assertTrue(DeliveryPolicy.uncertain(item(emptyList()).copy(lastError = "awaiting_ack")))
        assertFalse(DeliveryPolicy.uncertain(item(emptyList()).copy(lastError = "failed: rpc_4000")))
    }
}
