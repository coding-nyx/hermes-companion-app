package app.hermes.companion.domain

import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.MessageRole

data class RewindSubmit(
    val rowId: Long,
    val empty: Boolean = false,
)

object RewindPolicy {
    fun durableRowId(id: String): Long? = id.trim().toLongOrNull()

    fun canTarget(message: ChatMessage): Boolean =
        message.role == MessageRole.USER &&
            !message.queued &&
            !message.streaming &&
            durableRowId(message.id) != null

    fun isFirstUserTurn(messages: List<ChatMessage>, targetId: String): Boolean {
        val first = messages.firstOrNull { it.role == MessageRole.USER && !it.queued } ?: return true
        return first.id == targetId
    }

    fun dropFrom(messages: List<ChatMessage>, targetId: String): List<ChatMessage> {
        val idx = messages.indexOfFirst { it.id == targetId }
        if (idx < 0) return messages
        return messages.take(idx)
    }

    fun jsonExtras(rewind: RewindSubmit?): String {
        if (rewind == null) return ""
        val empty = if (rewind.empty) ""","confirm_empty_truncate":true""" else ""
        return ""","truncate_before_row_id":${rewind.rowId},"confirm_truncate":true$empty"""
    }

    fun rebindUserRowIds(messages: List<ChatMessage>, survivors: List<Long?>): List<ChatMessage> {
        val userIdx = messages.mapIndexedNotNull { index, message ->
            if (message.role == MessageRole.USER && !message.queued) index else null
        }
        if (userIdx.isEmpty()) return messages
        val next = messages.toMutableList()
        userIdx.forEachIndexed { ordinal, msgIndex ->
            if (ordinal >= survivors.size) return@forEachIndexed
            val current = next[msgIndex]
            val fresh = survivors[ordinal]
            next[msgIndex] = current.copy(id = fresh?.toString() ?: "x-${current.id}")
        }
        return next
    }

    fun survivorRowIds(raw: List<Any?>): List<Long?> = raw.map { value ->
        when (value) {
            null -> null
            is Long -> value
            is Int -> value.toLong()
            is Number -> {
                val d = value.toDouble()
                val n = value.toLong()
                if (d == n.toDouble()) n else null
            }
            else -> null
        }
    }
}
