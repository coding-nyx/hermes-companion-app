package app.hermes.companion.domain

/**
 * Minimal JSON string encoding for the device lane.
 *
 * The lane builds its result payloads by hand, and the original quoting escaped only `\` and
 * `"`. Anything else went through raw — so a single newline produced a malformed payload that
 * the host could not parse, and the command appeared to *time out* rather than fail. That is a
 * nasty failure mode because it depends on the data: call-log numbers and contact names never
 * contain control characters, while SMS bodies, notification texts, snapshot node labels and
 * clipboard contents routinely do.
 *
 * This escapes every character JSON requires: the two mandatory ones, the five short forms, and
 * anything else below U+0020 as a `\uXXXX` sequence.
 */
object JsonText {

    fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '' -> append("\\f")
                else ->
                    if (ch < ' ') {
                        // Any other C0 control character must be a \u escape; raw is illegal.
                        append("\\u")
                        append(HEX[(ch.code shr 12) and 0xF])
                        append(HEX[(ch.code shr 8) and 0xF])
                        append(HEX[(ch.code shr 4) and 0xF])
                        append(HEX[ch.code and 0xF])
                    } else {
                        append(ch)
                    }
            }
        }
        append('"')
    }

    /** Render a flat map as a JSON object; numbers and booleans unquoted, everything else quoted. */
    fun obj(row: Map<String, Any?>): String =
        row.entries.joinToString(",", "{", "}") { (key, value) ->
            val rendered = when (value) {
                null -> "null"
                is Number, is Boolean -> value.toString()
                else -> quote(value.toString())
            }
            "${quote(key)}:$rendered"
        }

    private val HEX = "0123456789abcdef".toCharArray()
}
