package app.hermes.hands.engine

/** Memory-only redaction for locally entered values; never serialized or persisted. */
internal class PrivateValues {
    private val values = ArrayDeque<String>()
    @Synchronized fun remember(value: String) {
        require(value.length <= MAX_CHARS)
        if (value.isEmpty()) return
        values.remove(value)
        values.addLast(value)
        while (values.size > MAX_VALUES) values.removeFirst()
    }

    /** Redact before the caller truncates a label, so long values cannot leak a prefix. */
    @Synchronized fun sanitize(text: String): String {
        if (text.isEmpty() || values.isEmpty()) return text
        val ordered = values.sortedByDescending { it.length }
        val output = StringBuilder(text.length)
        var offset = 0
        while (offset < text.length) {
            var next = -1
            var length = 0
            for (value in ordered) {
                val at = text.indexOf(value, offset)
                if (at >= 0 && (next < 0 || at < next)) { next = at; length = value.length }
            }
            if (next < 0) { output.append(text, offset, text.length); break }
            output.append(text, offset, next).append("[private]")
            offset = next + length
        }
        return output.toString()
    }

    @Synchronized fun clear() = values.clear()
    companion object { const val MAX_VALUES = 32; const val MAX_CHARS = 16_384 }
}
