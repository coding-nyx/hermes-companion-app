package app.hermes.companion.model

data class ContextCategory(val id: String, val label: String, val tokens: Long)

data class SessionContext(
    val used: Long?, val maximum: Long?, val estimatedTotal: Long?,
    val estimated: Boolean?, val source: String, val model: String,
    val categories: List<ContextCategory>,
) {
    // Native Hermes reports percent in 0..100. Derive from token counts instead of
    // guessing whether older providers use a fraction; unknown is not zero usage.
    val percent: Double? get() = if (used != null && maximum != null && maximum > 0)
        (used.toDouble() / maximum * 100).coerceIn(0.0, 100.0) else null
}

enum class CompressionStatus { COMPRESSED, PENDING, ABORTED, UNKNOWN }

data class SessionCompression(
    val status: CompressionStatus, val lockHeld: Boolean,
    val message: String, val headline: String, val tokenLine: String, val note: String,
    val beforeTokens: Long?, val afterTokens: Long?,
    /** Null means omitted; never clear history from a missing messages payload. */
    val messages: List<ChatMessage>?,
)
