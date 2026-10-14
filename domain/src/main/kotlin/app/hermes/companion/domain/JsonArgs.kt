package app.hermes.companion.domain

/**
 * Reads flat device-lane command arguments (a JSON object serialised by the host).
 *
 * Numbers are read from their literal text rather than through `Float`: a Float carries a
 * 24-bit mantissa, so an epoch-millisecond argument (`begin_ms`, `since_ms`, …) came out
 * rounded to steps of ~131 s and a calendar event landed a minute or more off.
 */
object JsonArgs {
    private const val NUMBER = "-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"

    /**
     * String argument, fully JSON-decoded. The host serialises with standard escapes, so an SMS
     * body "Hi<newline>Bye" arrives as `Hi\nBye`; decoding only `\"` and `\\` sent the literal
     * backslash-n on to the recipient.
     */
    fun string(raw: String, key: String): String? {
        val match = Regex("\"${Regex.escape(key)}\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(raw) ?: return null
        return unescape(match.groupValues[1])
    }

    /** Decode the body of a JSON string literal (without the quotes). Malformed escapes pass through. */
    fun unescape(body: String): String {
        if ('\\' !in body) return body
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                out.append(c)
                i++
                continue
            }
            when (val e = body[i + 1]) {
                '"', '\\', '/' -> out.append(e)
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'u' -> {
                    val hex = body.substring(i + 2, minOf(i + 6, body.length))
                    val code = if (hex.length == 4 && hex.all { it.isLetterOrDigit() }) hex.toIntOrNull(16) else null
                    if (code == null) {
                        out.append("\\u")
                    } else {
                        // Surrogate pairs arrive as two \u escapes; appending each char rebuilds them.
                        out.append(code.toChar())
                        i += 4
                    }
                }
                else -> out.append('\\').append(e)
            }
            i += 2
        }
        return out.toString()
    }

    /** The number literal for [key] exactly as sent, e.g. `1759230000000`. */
    fun numberText(raw: String, key: String): String? =
        Regex("\"${Regex.escape(key)}\"\\s*:\\s*($NUMBER)").find(raw)?.groupValues?.getOrNull(1)

    fun double(raw: String, key: String): Double? = numberText(raw, key)?.toDoubleOrNull()

    /** Exact for integer literals up to Long range; `1.7E12` / `1700000000000.0` also accepted. */
    fun long(raw: String, key: String): Long? {
        val text = numberText(raw, key) ?: return null
        text.toLongOrNull()?.let { return it }
        val d = text.toDoubleOrNull() ?: return null
        return if (d.isFinite()) d.toLong() else null
    }

    /** Integer argument as canonical text (`1700000000000.0` → `1700000000000`), else the literal. */
    fun numberArg(raw: String, key: String): String? {
        val text = numberText(raw, key) ?: return null
        text.toLongOrNull()?.let { return it.toString() }
        val d = text.toDoubleOrNull() ?: return text
        return if (d.isFinite() && d == Math.rint(d) && kotlin.math.abs(d) < 9.0E15) d.toLong().toString() else text
    }
}
