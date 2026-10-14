package app.hermes.companion.voice

import app.hermes.companion.domain.VoiceTextFilter

/** Keep raw prefix ownership: final text may revise unsent clauses, never already-sent speech. */
internal class StreamingSpeechText {
    private val raw = StringBuilder()
    private var sent = 0
    data class Batch(val text: String, val end: Int)

    fun append(delta: String) {
        require(raw.length + delta.length <= 64_000) { "Reply is too long to read aloud" }
        raw.append(delta)
    }

    fun reconcile(final: String): Boolean {
        if (!final.startsWith(raw.substring(0, sent))) return false
        require(final.length <= 64_000) { "Reply is too long to read aloud" }
        raw.clear(); raw.append(final)
        return true
    }

    fun next(final: Boolean): Batch? {
        val end = if (final) raw.length else stableEnd()
        if (end <= sent) return null
        return Batch(clean(raw.substring(sent, end)), end)
    }
    fun commit(batch: Batch) { sent = batch.end }
    fun allText(): String = clean(raw.toString())
    fun clear() { raw.clear(); sent = 0 }

    private fun stableEnd(): Int {
        var i = sent
        var codeFence = false
        var inlineCode = false
        var brackets = 0
        var parentheses = 0
        var thinking = false
        var boundary = sent
        while (i < raw.length) {
            when {
                raw.startsWith("<think>", i) -> { thinking = true; i += 7; continue }
                raw.startsWith("</think>", i) -> { thinking = false; i += 8; continue }
                raw.startsWith("```", i) -> { codeFence = !codeFence; i += 3; continue }
                raw[i] == '`' && !codeFence -> inlineCode = !inlineCode
                !codeFence && !inlineCode && !thinking -> {
                    when (raw[i]) {
                        '[' -> brackets++
                        ']' -> brackets = (brackets - 1).coerceAtLeast(0)
                        '(' -> parentheses++
                        ')' -> parentheses = (parentheses - 1).coerceAtLeast(0)
                    }
                    if (brackets == 0 && parentheses == 0 &&
                        (raw[i] == '\n' || raw[i] in "!?" ||
                            (raw[i] == '.' && i + 1 < raw.length && raw[i + 1].isWhitespace()))) boundary = i + 1
                }
            }
            i++
        }
        return boundary
    }

    private fun clean(value: String) = VoiceTextFilter.cleanForSpeech(
        value.replace(Regex("<think>[\\s\\S]*?(?:</think>|$)"), ""))
}
