package app.hermes.companion.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.domain.MdBlock
import app.hermes.companion.domain.MdSpan
import app.hermes.companion.domain.MarkdownRender

@Composable
fun MarkdownBlocks(
    text: String,
    modifier: Modifier = Modifier,
    onFetchMedia: suspend (String) -> ByteArray? = { null },
    onOpenMedia: (url: String, mime: String, name: String) -> Unit = { _, _, _ -> },
) {
    val blocks = remember(text) { MarkdownRender.blocks(text) }
    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            val previous = blocks.getOrNull(index - 1)
            val gap = when {
                previous == null -> 0.dp
                (previous is MdBlock.Bullet || previous is MdBlock.Ordered) &&
                    (block is MdBlock.Bullet || block is MdBlock.Ordered) -> 2.dp
                previous is MdBlock.Quote && block is MdBlock.Quote -> 2.dp
                block is MdBlock.Heading -> 10.dp
                previous is MdBlock.Heading -> 4.dp
                else -> 8.dp
            }
            if (gap > 0.dp) Spacer(Modifier.height(gap))
            when (block) {
                is MdBlock.Heading -> Text(
                    text = block.spans.toAnnotated(links = true),
                    style = CompanionType.Body.copy(
                        fontSize = when (block.level) {
                            1 -> 20.sp
                            2 -> 18.sp
                            else -> 16.sp
                        },
                        fontWeight = FontWeight.Medium,
                        color = CompanionColor.Signal,
                    ),
                )
                is MdBlock.Paragraph -> MarkdownInline(block.spans, CompanionType.Body)
                is MdBlock.Bullet -> Row {
                    Text("▸  ", style = CompanionType.Body.copy(color = CompanionColor.Signal))
                    MarkdownInline(block.spans, CompanionType.Body, Modifier.weight(1f, fill = false))
                }
                is MdBlock.Ordered -> Row {
                    Text("${block.index}.  ", style = CompanionType.Body.copy(color = CompanionColor.Signal))
                    MarkdownInline(block.spans, CompanionType.Body, Modifier.weight(1f, fill = false))
                }
                is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier
                            .width(2.dp)
                            .fillMaxHeight()
                            .background(CompanionColor.Signal),
                    )
                    Spacer(Modifier.width(CompanionSpace.Sm))
                    MarkdownInline(block.spans, CompanionType.Body.copy(color = CompanionColor.TextDim))
                }
                is MdBlock.Image -> MediaThumb(
                    url = block.url,
                    alt = block.alt,
                    onOpen = { onOpenMedia(block.url, "image/*", block.alt) },
                    onFetch = onFetchMedia,
                )
                is MdBlock.Table -> TableBlock(block)
                is MdBlock.Fence -> CodeBlockView(block)
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MdBlock.Fence) {
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val lineCount = remember(block.text) { block.text.lines().size }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(CompanionSpace.Hairline, CompanionColor.LineStrong)
            .background(CompanionColor.VoidElevated)
            .testTag("chat.code"),
    ) {
        // Code Block Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(CompanionColor.Line)
                .padding(horizontal = CompanionSpace.Sm, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Sm),
            ) {
                Text(
                    text = block.lang.ifBlank { "code" }.uppercase(),
                    style = CompanionType.MonoSmall.copy(
                        color = CompanionColor.Signal,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
                if (lineCount > 1) {
                    Text(
                        text = "$lineCount lines",
                        style = CompanionType.MonoSmall.copy(
                            color = CompanionColor.TextMute,
                            fontSize = 13.sp,
                        ),
                    )
                }
            }

            // 1-Tap Copy Action
            Row(
                modifier = Modifier
                    .clickable {
                        clipboardManager.setText(AnnotatedString(block.text))
                        copied = true
                        scope.launch {
                            kotlinx.coroutines.delay(1800)
                            copied = false
                        }
                    }
                    .heightIn(min = 48.dp).widthIn(min = 48.dp)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (copied) "COPIED ✓" else "COPY",
                    style = CompanionType.MonoSmall.copy(
                        color = if (copied) CompanionColor.Signal else CompanionColor.TextDim,
                        fontSize = 13.sp,
                    ),
                )
            }
        }

        // Code Content
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(CompanionSpace.Sm),
        ) {
            Text(
                text = block.text,
                style = CompanionType.Mono.copy(
                    color = CompanionColor.Text,
                    fontFamily = FontFamily.Monospace,
                ),
                softWrap = false,
            )
        }
    }
}

@Composable
fun MarkdownInline(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    links: Boolean = false,
) {
    val spans = remember(text) { MarkdownRender.inline(text) }
    MarkdownInline(spans, style, modifier, links)
}

@Composable
private fun MarkdownInline(
    spans: List<MdSpan>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    links: Boolean = true,
) {
    Text(text = spans.toAnnotated(links), style = style, modifier = modifier)
}

@Composable
private fun TableBlock(block: MdBlock.Table) {
    val cellWidth = 96.dp * androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceAtLeast(1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(CompanionSpace.Hairline, CompanionColor.Line)
            .horizontalScroll(rememberScrollState())
            .testTag("chat.table"),
    ) {
        Row(Modifier.background(CompanionColor.SignalDim).padding(horizontal = 8.dp, vertical = 6.dp)) {
            block.headers.forEach { cell ->
                Text(
                    text = cell,
                    style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                    modifier = Modifier.width(cellWidth).padding(end = 8.dp),
                )
            }
        }
        block.rows.forEach { row ->
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Text),
                        modifier = Modifier.width(cellWidth).padding(end = 8.dp),
                    )
                }
            }
        }
    }
}

private fun List<MdSpan>.toAnnotated(links: Boolean): AnnotatedString = buildAnnotatedString {
    forEach { span ->
        val href = span.href
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) CompanionColor.SignalDim else Color.Unspecified,
            color = if (href != null) CompanionColor.Signal else Color.Unspecified,
        )
        if (links && href != null) {
            withLink(LinkAnnotation.Url(href)) {
                withStyle(style) { append(span.text) }
            }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
