package app.hermes.companion.domain

object DictationText {
    /** Partial and final results replace the same provisional span after the original draft. */
    fun combine(original: String, transcript: String): String {
        val spoken = transcript.trim()
        if (spoken.isEmpty()) return original
        return original + (if (original.isEmpty() || original.last().isWhitespace()) "" else " ") + spoken
    }
}
