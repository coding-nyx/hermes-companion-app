package app.hermes.companion.domain

data class MdSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val href: String? = null,
)

sealed class MdBlock {
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock()
    data class Paragraph(val spans: List<MdSpan>) : MdBlock()
    data class Bullet(val spans: List<MdSpan>) : MdBlock()
    data class Ordered(val index: Int, val spans: List<MdSpan>) : MdBlock()
    data class Fence(val lang: String, val text: String) : MdBlock()
    data class Quote(val spans: List<MdSpan>) : MdBlock()
    data class Image(val alt: String, val url: String) : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
}

/** Small markdown subset for chat. No library. Unterminated fences render as code. */
object MarkdownRender {
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val bullet = Regex("""^[-*]\s+(.*)$""")
    private val ordered = Regex("""^(\d+)\.\s+(.*)$""")
    private val imageOnly = Regex("""^!\[([^\]]*)]\(([^)]+)\)$""")
    private val tableSep = Regex("""^\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)+\|?\s*$""")

    fun blocks(src: String): List<MdBlock> {
        if (src.isEmpty()) return emptyList()
        val lines = src.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```")) {
                val lang = trimmed.removePrefix("```").trim()
                val body = StringBuilder()
                i++
                while (i < lines.size) {
                    if (lines[i].trimStart().startsWith("```")) {
                        i++
                        break
                    }
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                out += MdBlock.Fence(lang, body.toString())
                continue
            }
            if (line.isBlank()) {
                i++
                continue
            }
            val imageMatch = imageOnly.matchEntire(trimmed)
            if (imageMatch != null) {
                out += MdBlock.Image(imageMatch.groupValues[1], imageMatch.groupValues[2].trim())
                i++
                continue
            }
            if (trimmed.contains('|') && i + 1 < lines.size && tableSep.matches(lines[i + 1].trim())) {
                val headers = splitRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].contains('|') && !lines[i].isBlank()) {
                    if (tableSep.matches(lines[i].trim())) {
                        i++
                        continue
                    }
                    rows += splitRow(lines[i].trim())
                    i++
                }
                out += MdBlock.Table(headers, rows)
                continue
            }
            val headingMatch = heading.matchEntire(trimmed)
            if (headingMatch != null) {
                out += MdBlock.Heading(headingMatch.groupValues[1].length, inline(headingMatch.groupValues[2]))
                i++
                continue
            }
            if (trimmed.startsWith(">")) {
                out += MdBlock.Quote(inline(trimmed.removePrefix(">").trimStart()))
                i++
                continue
            }
            val bulletMatch = bullet.matchEntire(trimmed)
            if (bulletMatch != null) {
                out += MdBlock.Bullet(inline(bulletMatch.groupValues[1]))
                i++
                continue
            }
            val orderedMatch = ordered.matchEntire(trimmed)
            if (orderedMatch != null) {
                out += MdBlock.Ordered(orderedMatch.groupValues[1].toInt(), inline(orderedMatch.groupValues[2]))
                i++
                continue
            }
            val buf = StringBuilder(line)
            i++
            while (i < lines.size) {
                val next = lines[i]
                val t = next.trimStart()
                if (next.isBlank()) break
                if (t.startsWith("```") || heading.matches(t) || t.startsWith(">") ||
                    bullet.matches(t) || ordered.matches(t)
                ) {
                    break
                }
                buf.append('\n').append(next)
                i++
            }
            out += MdBlock.Paragraph(inline(buf.toString()))
        }
        return out
    }

    fun inline(src: String): List<MdSpan> = parseInline(src, 0, src.length).first

    private fun parseInline(src: String, start: Int, end: Int, stop: String? = null): Pair<List<MdSpan>, Int> {
        val out = mutableListOf<MdSpan>()
        fun emit(
            text: String,
            bold: Boolean = false,
            italic: Boolean = false,
            code: Boolean = false,
            href: String? = null,
        ) {
            if (text.isEmpty()) return
            val last = out.lastOrNull()
            if (last != null &&
                last.bold == bold &&
                last.italic == italic &&
                last.code == code &&
                last.href == href
            ) {
                out[out.lastIndex] = last.copy(text = last.text + text)
            } else {
                out += MdSpan(text, bold, italic, code, href)
            }
        }
        var i = start
        while (i < end) {
            if (stop != null && src.startsWith(stop, i)) return out to i
            when {
                src[i] == '\\' && i + 1 < end -> {
                    emit(src[i + 1].toString())
                    i += 2
                }
                src[i] == '`' -> {
                    val close = src.indexOf('`', i + 1).takeIf { it in (i + 1) until end }
                    if (close != null) {
                        emit(src.substring(i + 1, close), code = true)
                        i = close + 1
                    } else {
                        emit("`")
                        i++
                    }
                }
                src[i] == '[' -> {
                    val rb = src.indexOf(']', i + 1).takeIf { it in (i + 1) until end }
                    val hrefStart = rb?.let { it + 1 }?.takeIf { it < end && src[it] == '(' }
                    val rp = hrefStart?.let { src.indexOf(')', it + 1).takeIf { c -> c in (it + 1) until end } }
                    if (rb != null && hrefStart != null && rp != null) {
                        emit(src.substring(i + 1, rb), href = src.substring(hrefStart + 1, rp))
                        i = rp + 1
                    } else {
                        emit("[")
                        i++
                    }
                }
                src.startsWith("**", i) -> {
                    val inner = parseInline(src, i + 2, end, stop = "**")
                    if (inner.second < end && src.startsWith("**", inner.second)) {
                        inner.first.forEach { emit(it.text, bold = true, italic = it.italic, code = it.code, href = it.href) }
                        i = inner.second + 2
                    } else {
                        emit("*")
                        i++
                    }
                }
                src[i] == '*' -> {
                    val inner = parseInline(src, i + 1, end, stop = "*")
                    if (inner.second < end && src[inner.second] == '*') {
                        inner.first.forEach { emit(it.text, bold = it.bold, italic = true, code = it.code, href = it.href) }
                        i = inner.second + 1
                    } else {
                        emit("*")
                        i++
                    }
                }
                else -> {
                    val next = nextSpecial(src, i + 1, end, stop)
                    emit(src.substring(i, next))
                    i = next
                }
            }
        }
        return out to i
    }

    private fun splitRow(line: String): List<String> {
        val trimmed = line.trim().removePrefix("|").removeSuffix("|")
        return trimmed.split('|').map { it.trim() }
    }

    private fun nextSpecial(src: String, from: Int, end: Int, stop: String?): Int {
        var i = from
        while (i < end) {
            if (stop != null && src.startsWith(stop, i)) return i
            when (src[i]) {
                '\\', '`', '[', '*' -> return i
            }
            i++
        }
        return end
    }
}
