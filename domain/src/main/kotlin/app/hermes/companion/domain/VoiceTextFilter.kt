package app.hermes.companion.domain

/**
 * Text processing utilities for real-time speech synthesis and conversational voice streaming.
 */
object VoiceTextFilter {
    /**
     * Strip technical markdown, code fences, inline code, and format characters
     * so that the synthesized speech sounds natural.
     */
    fun cleanForSpeech(raw: String): String {
        return raw
            .replace(Regex("```[\\s\\S]*?```"), " code snippet ")
            .replace(Regex("`[^`]*`"), "")
            .replace(Regex("[*#_~]"), "")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .trim()
    }

    /**
     * Splits streaming text into completed clauses and remaining pending buffer.
     * Cuts on natural boundaries: '?', '!', '\n', ':', and '.' followed by whitespace.
     */
    fun extractNextClause(buffer: String): Pair<String, String>? {
        if (buffer.isBlank()) return null

        var cutIdx = -1
        for (i in buffer.indices) {
            val ch = buffer[i]
            if (ch == '?' || ch == '!' || ch == '\n' || ch == ':') {
                cutIdx = i + 1
                break
            } else if (ch == '.' && (i + 1 < buffer.length && buffer[i + 1].isWhitespace())) {
                cutIdx = i + 1
                break
            }
        }

        if (cutIdx > 0) {
            val clause = buffer.substring(0, cutIdx).trim()
            val remaining = buffer.substring(cutIdx)
            return Pair(clause, remaining)
        }

        return null
    }
}
