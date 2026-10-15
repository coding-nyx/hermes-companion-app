package app.hermes.companion.voice

/** A spoken preview; the authoritative answer and explicit read-aloud remain complete. */
class BriefSpeechOutput(private val output: SpeechOutput) : SpeechOutput {
    private val source = StringBuilder()
    private var forwarded = 0
    private var truncated = false
    private var finished = false
    private var finalPreview: SpokenPreview? = null

    override fun feedToken(delta: String) {
        if (finished || delta.isEmpty()) return
        if (!truncated) {
            val room = (4096 - source.length).coerceAtLeast(0)
            source.append(delta.take(room))
            val preview = spokenPreview(source.toString(), final = false)
            emit(preview.text)
            truncated = preview.truncated || delta.length > room
        }
    }

    override fun reconcileFinal(text: String) { finalPreview = spokenPreview(text, final = true) }

    override fun finishFeed() {
        if (finished) return
        finished = true
        if (source.isBlank()) finalPreview?.let { source.append(it.text) }
        val preview = spokenPreview(source.toString(), final = true)
        emit(preview.text)
        val more = truncated || preview.truncated || finalPreview?.truncated == true
        val suffix = if (more) " The rest is in the chat." else ""
        if (suffix.isNotEmpty()) output.feedToken(suffix)
        // Buffered providers may use the authoritative text; already-spoken providers never replay it.
        output.reconcileFinal((finalPreview?.text ?: preview.text) + suffix)
        output.finishFeed()
    }

    private fun emit(text: String) {
        if (text.length > forwarded) {
            output.feedToken(text.substring(forwarded))
            forwarded = text.length
        }
    }

    private fun reset() { source.clear(); forwarded = 0; truncated = false; finished = false; finalPreview = null }
    override fun stop() { reset(); output.stop() }
    override fun shutdown() { reset(); output.shutdown() }
}

internal data class SpokenPreview(val text: String, val truncated: Boolean)

internal fun spokenPreview(text: String, final: Boolean): SpokenPreview {
    var end = text.length
    val words = Regex("\\S+").findAll(text).take(46).toList()
    if (words.size > 45) end = minOf(end, words[45].range.first)
    var sentences = 0
    for (match in Regex("[.!?](?=\\s|$)").findAll(text)) {
        if (!final && match.range.last == text.lastIndex) continue
        if (++sentences == 2) { end = minOf(end, match.range.last + 1); break }
    }
    if (end > 600) {
        val boundary = text.take(600).indexOfLast(Char::isWhitespace)
        end = if (boundary >= 0) boundary else 0
    }
    val truncated = text.substring(end).isNotBlank()
    if (!final && end == text.length) {
        // Hold the unfinished word so token splits cannot cut a word or decimal in half.
        end = text.indexOfLast(Char::isWhitespace).let { if (it < 0) 0 else it + 1 }
    }
    return SpokenPreview(text.take(end).trimEnd(), truncated)
}
