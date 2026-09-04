package app.hermes.companion.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(CompanionSpace.Sm)) {
        blocks.forEach { block ->
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
                    Text("▸  ", style = CompanionType.Mono.copy(color = CompanionColor.Signal))
                    MarkdownInline(block.spans, CompanionType.Body, Modifier.weight(1f, fill = false))
                }
                is MdBlock.Ordered -> Row {
                    Text("${block.index}.  ", style = CompanionType.Mono.copy(color = CompanionColor.Signal))
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
                is MdBlock.Fence -> Box(
                    Modifier
                        .fillMaxWidth()
                        .background(CompanionColor.SignalDim)
                        .border(CompanionSpace.Hairline, CompanionColor.Line)
                        .horizontalScroll(rememberScrollState())
                        .padding(CompanionSpace.Sm)
                        .testTag("chat.code"),
                ) {
                    Column {
                        if (block.lang.isNotBlank()) {
                            Text(
                                text = block.lang,
                                style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
                            )
                        }
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
                    modifier = Modifier.width(96.dp).padding(end = 8.dp),
                )
            }
        }
        block.rows.forEach { row ->
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                row.forEach { cell ->
                    Text(
                        text = cell,
                        style = CompanionType.MonoSmall.copy(color = CompanionColor.Text),
                        modifier = Modifier.width(96.dp).padding(end = 8.dp),
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
