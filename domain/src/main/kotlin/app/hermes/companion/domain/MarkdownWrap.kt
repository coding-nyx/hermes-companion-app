package app.hermes.companion.domain

import kotlin.math.max
import kotlin.math.min

object MarkdownWrap {
    data class Edit(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
    )

    fun bold(edit: Edit): Edit = wrap(edit, "**")

    fun italic(edit: Edit): Edit = wrap(edit, "*")

    fun code(edit: Edit): Edit = wrap(edit, "`")

    fun bullet(edit: Edit): Edit = prefixLines(edit, "- ")

    fun wrap(edit: Edit, open: String, close: String = open): Edit {
        val text = edit.text
        val start = min(edit.selectionStart, edit.selectionEnd).coerceIn(0, text.length)
        val end = max(edit.selectionStart, edit.selectionEnd).coerceIn(0, text.length)
        val selected = text.substring(start, end)
        if (selected.startsWith(open) && selected.endsWith(close) &&
            selected.length >= open.length + close.length
        ) {
            val inner = selected.removePrefix(open).removeSuffix(close)
            return Edit(text.substring(0, start) + inner + text.substring(end), start, start + inner.length)
        }
        if (start >= open.length && end + close.length <= text.length &&
            text.substring(start - open.length, start) == open &&
            text.substring(end, end + close.length) == close
        ) {
            val next = text.substring(0, start - open.length) + selected + text.substring(end + close.length)
            val at = start - open.length
            return Edit(next, at, at + selected.length)
        }
        val next = text.substring(0, start) + open + selected + close + text.substring(end)
        if (selected.isEmpty()) {
            val cursor = start + open.length
            return Edit(next, cursor, cursor)
        }
        return Edit(next, start, start + open.length + selected.length + close.length)
    }

    fun prefixLines(edit: Edit, prefix: String): Edit {
        val text = edit.text
        val start = min(edit.selectionStart, edit.selectionEnd).coerceIn(0, text.length)
        val end = max(edit.selectionStart, edit.selectionEnd).coerceIn(0, text.length)
        val lineStart = if (start == 0) 0 else {
            val nl = text.lastIndexOf('\n', start - 1)
            if (nl < 0) 0 else nl + 1
        }
        val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
        val block = text.substring(lineStart, lineEnd)
        val lines = block.split('\n')
        val prefixed = lines.all { it.isEmpty() || it.startsWith(prefix) }
        val rewritten = lines.joinToString("\n") { line ->
            when {
                line.isEmpty() -> line
                prefixed -> line.removePrefix(prefix)
                line.startsWith(prefix) -> line
                else -> prefix + line
            }
        }
        val next = text.substring(0, lineStart) + rewritten + text.substring(lineEnd)
        return Edit(next, lineStart, lineStart + rewritten.length)
    }
}
