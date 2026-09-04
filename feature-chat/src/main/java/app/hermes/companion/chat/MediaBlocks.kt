package app.hermes.companion.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.model.ChatBlock
import app.hermes.companion.model.ChatBlockKind
import app.hermes.companion.model.ChatMessage
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessageBlocks(
    message: ChatMessage,
    onFetchMedia: suspend (String) -> ByteArray?,
    onOpenMedia: (ChatBlock) -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = message.blocks.ifEmpty {
        if (message.text.isBlank()) emptyList()
        else listOf(ChatBlock(kind = ChatBlockKind.TEXT, text = message.text))
    }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block.kind) {
                ChatBlockKind.TEXT -> MarkdownBlocks(
                    text = block.text,
                    onFetchMedia = onFetchMedia,
                    onOpenMedia = { url, mime, name ->
                        onOpenMedia(ChatBlock(kind = ChatBlockKind.IMAGE, url = url, mime = mime, name = name, alt = name))
                    },
                )
                ChatBlockKind.IMAGE -> MediaThumb(
                    url = block.url,
                    alt = block.alt.ifBlank { block.name },
                    onOpen = { onOpenMedia(block) },
                    onFetch = onFetchMedia,
                )
                ChatBlockKind.VIDEO -> MediaChip(
                    label = "VIDEO · ${block.name.ifBlank { "clip" }}",
                    tag = "chat.video",
                    onClick = { onOpenMedia(block) },
                )
                ChatBlockKind.FILE -> MediaChip(
                    label = "FILE · ${block.name.ifBlank { "document" }}",
                    tag = "chat.file",
                    onClick = { onOpenMedia(block) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaThumb(
    url: String,
    alt: String,
    onOpen: () -> Unit,
    onFetch: suspend (String) -> ByteArray?,
    modifier: Modifier = Modifier,
) {
    var bytes by remember(url) { mutableStateOf<ByteArray?>(decodeDataUri(url)) }
    LaunchedEffect(url) {
        if (bytes == null && url.isNotBlank()) {
            bytes = withContext(Dispatchers.IO) { onFetch(url) }
        }
    }
    val bitmap = remember(bytes) {
        bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = alt,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .widthIn(max = 240.dp)
                .heightIn(max = 240.dp)
                .border(CompanionSpace.Hairline, CompanionColor.Signal)
                .combinedClickable(onClick = onOpen, onLongClick = onOpen)
                .testTag("chat.image"),
        )
    } else {
        MediaChip(label = "IMAGE · ${alt.ifBlank { "media" }}", tag = "chat.image.pending", onClick = onOpen)
    }
}

@Composable
fun MediaChip(label: String, tag: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
        modifier = Modifier
            .padding(vertical = 4.dp)
            .border(CompanionSpace.Hairline, CompanionColor.Signal)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .testTag(tag),
    )
}

internal fun decodeDataUri(url: String): ByteArray? {
    val marker = "base64,"
    val at = url.indexOf(marker, ignoreCase = true)
    if (!url.startsWith("data:", ignoreCase = true) || at < 0) return null
    return runCatching { Base64.getDecoder().decode(url.substring(at + marker.length).replace("\\s".toRegex(), "")) }
        .getOrNull()
}
