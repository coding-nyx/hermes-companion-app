package app.hermes.companion.domain

/**
 * Minimal SGR (colour/weight) parser for tmux `capture-pane -e` output (P23 pane snapshots).
 * Not a terminal emulator: cursor movement, clears and modes are stripped; only text and
 * `ESC [ … m` styling survive. Output is one list of [Span]s per line, ready for `AnnotatedString`.
 */
object AnsiText {
    private const val ESC = "\u001B"

    data class Span(
        val text: String,
        /** 0xAARRGGBB, null = default foreground. */
        val fg: Long? = null,
        val bg: Long? = null,
        val bold: Boolean = false,
        val dim: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val inverse: Boolean = false,
    ) {
        val plain: Boolean get() = fg == null && bg == null && !bold && !dim && !italic && !underline && !inverse
    }

    private data class Style(
        val fg: Long? = null, val bg: Long? = null, val bold: Boolean = false, val dim: Boolean = false,
        val italic: Boolean = false, val underline: Boolean = false, val inverse: Boolean = false,
    )

    /** The 16 base colours, tuned for the void background (same family as the app tokens). */
    val PALETTE16: List<Long> = listOf(
        0xFF3A4150, 0xFFFF4D6A, 0xFF00E5C3, 0xFFF5A524, 0xFF5AA9FF, 0xFFC792EA, 0xFF39D2C0, 0xFFE6EDF3,
        0xFF6B7482, 0xFFFF7A90, 0xFF5CF2D8, 0xFFFFC163, 0xFF8FC4FF, 0xFFDDB3F5, 0xFF7FE8DA, 0xFFFFFFFF,
    )

    private val CSI = Regex("$ESC\\[([0-9;?]*)([ -/]*)([@-~])")
    private val OSC = Regex("$ESC\\][^\u0007]*(\u0007|$ESC\\\\)")
    private val OTHER_ESC = Regex("$ESC[()][A-Za-z0-9]|$ESC[=>78MDEHc]")

    fun parse(raw: String): List<List<Span>> {
        val cleaned = OTHER_ESC.replace(OSC.replace(raw, ""), "")
        val lines = mutableListOf<List<Span>>()
        var style = Style()
        for (line in cleaned.split('\n')) {
            val spans = mutableListOf<Span>()
            var i = 0
            val buf = StringBuilder()
            fun flush() {
                if (buf.isNotEmpty()) {
                    spans += Span(buf.toString(), style.fg, style.bg, style.bold, style.dim, style.italic, style.underline, style.inverse)
                    buf.clear()
                }
            }
            while (i < line.length) {
                val m = CSI.matchAt(line, i)
                if (m != null) {
                    if (m.groupValues[3] == "m") {
                        flush()
                        style = applySgr(style, m.groupValues[1])
                    }
                    i = m.range.last + 1
                    continue
                }
                val ch = line[i]
                if (ch != '\r' && ch != '\u000F' && ch != '\u000E') buf.append(ch)
                i++
            }
            flush()
            lines += spans
        }
        // capture-pane ends with a newline; drop the trailing empty line it produces.
        if (lines.size > 1 && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        return lines
    }

    /** Plain text of the pane, one string per line. */
    fun plain(raw: String): List<String> = parse(raw).map { line -> line.joinToString("") { it.text } }

    private fun applySgr(base: Style, params: String): Style {
        if (params.isBlank()) return Style()
        val codes = params.split(';').map { it.toIntOrNull() ?: 0 }
        var s = base
        var i = 0
        while (i < codes.size) {
            when (val c = codes[i]) {
                0 -> s = Style()
                1 -> s = s.copy(bold = true)
                2 -> s = s.copy(dim = true)
                3 -> s = s.copy(italic = true)
                4 -> s = s.copy(underline = true)
                7 -> s = s.copy(inverse = true)
                22 -> s = s.copy(bold = false, dim = false)
                23 -> s = s.copy(italic = false)
                24 -> s = s.copy(underline = false)
                27 -> s = s.copy(inverse = false)
                39 -> s = s.copy(fg = null)
                49 -> s = s.copy(bg = null)
                in 30..37 -> s = s.copy(fg = PALETTE16[c - 30])
                in 90..97 -> s = s.copy(fg = PALETTE16[c - 90 + 8])
                in 40..47 -> s = s.copy(bg = PALETTE16[c - 40])
                in 100..107 -> s = s.copy(bg = PALETTE16[c - 100 + 8])
                38, 48 -> {
                    val (color, used) = extended(codes, i)
                    s = if (c == 38) s.copy(fg = color ?: s.fg) else s.copy(bg = color ?: s.bg)
                    i += used
                }
            }
            i++
        }
        return s
    }

    /** `38;5;n` / `38;2;r;g;b` starting at [at]; returns (colour, extra params consumed). */
    private fun extended(codes: List<Int>, at: Int): Pair<Long?, Int> {
        val mode = codes.getOrNull(at + 1) ?: return null to 0
        return when (mode) {
            5 -> xterm256(codes.getOrNull(at + 2) ?: 0) to 2
            2 -> {
                val r = codes.getOrNull(at + 2) ?: 0
                val g = codes.getOrNull(at + 3) ?: 0
                val b = codes.getOrNull(at + 4) ?: 0
                argb(r, g, b) to 4
            }
            else -> null to 1
        }
    }

    fun xterm256(n: Int): Long = when {
        n < 16 -> PALETTE16[n.coerceIn(0, 15)]
        n in 16..231 -> {
            val v = n - 16
            val steps = intArrayOf(0, 95, 135, 175, 215, 255)
            argb(steps[v / 36], steps[(v / 6) % 6], steps[v % 6])
        }
        else -> { val g = 8 + (n - 232).coerceIn(0, 23) * 10; argb(g, g, g) }
    }

    private fun argb(r: Int, g: Int, b: Int): Long =
        0xFF000000L or (r.coerceIn(0, 255).toLong() shl 16) or (g.coerceIn(0, 255).toLong() shl 8) or b.coerceIn(0, 255).toLong()
}
