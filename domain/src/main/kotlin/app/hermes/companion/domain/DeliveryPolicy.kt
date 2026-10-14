package app.hermes.companion.domain

import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole
import app.hermes.companion.model.OutboxItem

object DeliveryPolicy {
    /** An identical older message is not evidence that this submission was accepted. */
    fun landed(item: OutboxItem, messages: List<ChatMessage>): Boolean {
        val baseline = item.baselineUserIds ?: return false
        return messages.any {
            it.role == MessageRole.USER && it.id !in baseline &&
                it.text.trim() == item.text.trim() && item.text.isNotBlank()
        }
    }

    fun uncertain(item: OutboxItem): Boolean =
        item.baselineUserIds != null && (item.lastError == "awaiting_ack" || item.lastError.startsWith("uncertain"))
}
