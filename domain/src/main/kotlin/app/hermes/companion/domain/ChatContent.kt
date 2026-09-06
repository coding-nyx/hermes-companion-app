package app.hermes.companion.domain

import app.hermes.companion.model.ChatAttachment
import app.hermes.companion.model.ChatBlock
import app.hermes.companion.model.ChatBlockKind

object ChatContent {
    private val mdImage = Regex("""!\[([^\]]*)]\(([^)]+)\)""")
    private val dataUri = Regex("""^data:([^;,]+);base64,""", RegexOption.IGNORE_CASE)

    fun flatten(blocks: List<ChatBlock>): String =
        blocks.joinToString("\n") { block ->
            when (block.kind) {
                ChatBlockKind.TEXT -> block.text
                ChatBlockKind.IMAGE -> block.alt.ifBlank { block.name }.ifBlank { "image" }
                ChatBlockKind.VIDEO -> block.name.ifBlank { "video" }
                ChatBlockKind.FILE -> block.name.ifBlank { "file" }
            }
        }.trim()

    fun fromMarkdown(text: String): List<ChatBlock> {
        if (text.isBlank()) return emptyList()
        val blocks = mutableListOf<ChatBlock>()
        var cursor = 0
        mdImage.findAll(text).forEach { match ->
            val before = text.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) blocks += ChatBlock(kind = ChatBlockKind.TEXT, text = before)
            val alt = match.groupValues[1]
            val url = match.groupValues[2].trim()
            blocks += imageBlock(url, alt)
            cursor = match.range.last + 1
        }
        val tail = text.substring(cursor).trim()
        if (tail.isNotEmpty()) blocks += ChatBlock(kind = ChatBlockKind.TEXT, text = tail)
        if (blocks.isEmpty()) blocks += ChatBlock(kind = ChatBlockKind.TEXT, text = text)
        return blocks
    }

    fun imageBlock(url: String, alt: String = "", mime: String = "", name: String = ""): ChatBlock {
        val kindMime = mime.ifBlank { mimeOf(url) }
        return ChatBlock(
            kind = ChatBlockKind.IMAGE,
            url = url,
            alt = alt,
            name = name.ifBlank { alt },
            mime = kindMime,
        )
    }

    fun mediaBlock(url: String, mime: String, name: String = "", sizeBytes: Long = 0L): ChatBlock {
        val kind = kindOf(mime, name, url)
        return ChatBlock(
            kind = kind,
            url = url,
            name = name.ifBlank { url.substringAfterLast('/') },
            mime = mime,
            alt = name,
            sizeBytes = sizeBytes,
        )
    }

    fun screenshotBlocks(toolName: String?, detail: String?, text: String): List<ChatBlock> {
        val name = (toolName ?: "").lowercase()
        if ("screenshot" !in name && "image" !in name) return emptyList()
        val src = text.ifBlank { detail.orEmpty() }
        if (src.startsWith("data:image") || src.startsWith("http") || src.startsWith("/")) {
            return listOf(imageBlock(src, alt = "screenshot", name = "screenshot"))
        }
        if (src.length > 32 && src.all { it.isLetterOrDigit() || it in "+/=\n\r" }) {
            return listOf(imageBlock("data:image/png;base64,${src.replace("\\s".toRegex(), "")}", "screenshot"))
        }
        return emptyList()
    }

    fun formatToolOutput(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return trimmed
        val outputRegex = Regex(""""(?:output|result|stdout|content|text)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val errorRegex = Regex(""""(?:error|stderr)"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val outMatch = outputRegex.find(trimmed)?.groupValues?.get(1)
        val errMatch = errorRegex.find(trimmed)?.groupValues?.get(1)
        val unescapedOut = outMatch?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
        val unescapedErr = errMatch?.replace("\\n", "\n")?.replace("\\\"", "\"")?.replace("\\\\", "\\")
        return when {
            !unescapedOut.isNullOrBlank() && !unescapedErr.isNullOrBlank() -> "$unescapedOut\n\nERROR: $unescapedErr"
            !unescapedOut.isNullOrBlank() -> unescapedOut
            !unescapedErr.isNullOrBlank() && unescapedErr != "null" -> "ERROR: $unescapedErr"
            else -> trimmed
        }
    }

    fun kindOf(mime: String, name: String = "", url: String = ""): ChatBlockKind {
        val probe = "$mime $name $url".lowercase()
        return when {
            probe.contains("image") || probe.endsWith(".png") || probe.endsWith(".jpg") ||
                probe.endsWith(".jpeg") || probe.endsWith(".gif") || probe.endsWith(".webp") -> ChatBlockKind.IMAGE
            probe.contains("video") || probe.endsWith(".mp4") || probe.endsWith(".webm") ||
                probe.endsWith(".mov") -> ChatBlockKind.VIDEO
            else -> ChatBlockKind.FILE
        }
    }

    fun mimeOf(url: String): String {
        val data = dataUri.find(url)
        if (data != null) return data.groupValues[1]
        return when (url.substringAfterLast('.', "").lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "mov" -> "video/quicktime"
            "pdf" -> "application/pdf"
            "md" -> "text/markdown"
            "txt" -> "text/plain"
            else -> ""
        }
    }

    fun toSubmitParts(text: String, attachments: List<ChatAttachment>): String {
        if (attachments.isEmpty()) return ""
        val parts = buildString {
            append("[")
            var first = true
            if (text.isNotBlank()) {
                append("""{"type":"text","text":${json(text)}}""")
                first = false
            }
            for (item in attachments) {
                val url = item.uploadedUrl.ifBlank { item.localUri }
                if (!first) append(',')
                first = false
                val type = when (item.kind) {
                    ChatBlockKind.IMAGE -> "image"
                    ChatBlockKind.VIDEO -> "video"
                    ChatBlockKind.FILE -> "file"
                    ChatBlockKind.TEXT -> "text"
                }
                append("""{"type":"$type","url":${json(url)},"name":${json(item.name)},"mime":${json(item.mime)}}""")
            }
            append(']')
        }
        return ""","parts":$parts"""
    }

    fun userBlocks(text: String, attachments: List<ChatAttachment>): List<ChatBlock> {
        val out = mutableListOf<ChatBlock>()
        if (text.isNotBlank()) out += ChatBlock(kind = ChatBlockKind.TEXT, text = text)
        for (item in attachments) {
            val url = item.uploadedUrl.ifBlank { item.localUri }
            out += ChatBlock(
                kind = item.kind,
                url = url,
                name = item.name,
                mime = item.mime,
                alt = item.name,
                sizeBytes = item.sizeBytes,
            )
        }
        return out
    }

    private fun json(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                else -> append(ch)
            }
        }
        append('"')
    }
}
