package app.hermes.companion.domain

import app.hermes.companion.model.ChatMessage
import app.hermes.companion.model.HistoryPage

object HistoryPaging {
    const val PAGE = 80
    const val MAX = 500

    fun cap(limit: Int): Int = limit.coerceIn(1, MAX)

    fun tail(messages: List<ChatMessage>, limit: Int = PAGE): List<ChatMessage> {
        val n = cap(limit)
        return if (messages.size <= n) messages else messages.takeLast(n)
    }

    fun olderThan(messages: List<ChatMessage>, beforeId: String, limit: Int = PAGE): List<ChatMessage> {
        val n = cap(limit)
        val idx = messages.indexOfFirst { it.id == beforeId }
        if (idx <= 0) return emptyList()
        val older = messages.subList(0, idx)
        return if (older.size <= n) older else older.takeLast(n)
    }

    fun clip(messages: List<ChatMessage>, beforeId: String?, limit: Int = PAGE): HistoryPage {
        val n = cap(limit)
        val window = if (beforeId.isNullOrBlank()) tail(messages, n) else olderThan(messages, beforeId, n)
        val hasMore = window.size >= n || messages.size > window.size
        return HistoryPage(messages = window, hasMore = hasMore && window.isNotEmpty())
    }
}
