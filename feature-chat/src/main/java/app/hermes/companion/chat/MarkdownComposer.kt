package app.hermes.companion.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace
import app.hermes.companion.design.CompanionType
import app.hermes.companion.design.Hairline
import app.hermes.companion.domain.MarkdownWrap

@Composable
fun MarkdownComposer(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSend: () -> Unit = {},
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value) {
        if (value != field.text) field = TextFieldValue(value, TextRange(value.length))
    }
    fun format(transform: (MarkdownWrap.Edit) -> MarkdownWrap.Edit) {
        val out = transform(MarkdownWrap.Edit(field.text, field.selection.start, field.selection.end))
        field = TextFieldValue(out.text, TextRange(out.selectionStart, out.selectionEnd))
        onValueChange(out.text)
    }
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(CompanionColor.VoidElevated)
            .border(
                CompanionSpace.Hairline,
                if (focused) CompanionColor.Signal else CompanionColor.LineStrong,
                shape,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatChip("B", "chat.fmt.bold") { format(MarkdownWrap::bold) }
            FormatChip("I", "chat.fmt.italic") { format(MarkdownWrap::italic) }
            FormatChip("`", "chat.fmt.code") { format(MarkdownWrap::code) }
            FormatChip("-", "chat.fmt.list") { format(MarkdownWrap::bullet) }
        }
        Hairline()
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                onValueChange(it.text)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp, max = 130.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .onFocusChanged { focused = it.isFocused }
                // Hardware keyboards: Shift/Ctrl+Enter sends, plain Enter stays a newline.
                .onPreviewKeyEvent { ev ->
                    val enter = ev.key == Key.Enter || ev.key == Key.NumPadEnter
                    if (enter && ev.type == KeyEventType.KeyDown && (ev.isShiftPressed || ev.isCtrlPressed)) {
                        if (field.text.isNotBlank()) onSend()
                        true
                    } else {
                        false
                    }
                }
                .testTag("chat.draft"),
            textStyle = CompanionType.Body.copy(color = CompanionColor.Text),
            cursorBrush = SolidColor(CompanionColor.Signal),
            singleLine = false,
            minLines = 1,
            maxLines = 8,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
            ),
            visualTransformation = MarkdownVisual,
            decorationBox = { inner ->
                if (field.text.isEmpty()) {
                    Text(
                        text = if (focused) "Message Hermes… (↵ newline, ⇧↵ to send)" else "Message Hermes…",
                        style = CompanionType.Body.copy(color = CompanionColor.TextMute),
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun FormatChip(label: String, tag: String, onClick: () -> Unit) {
    val chipShape = RoundedCornerShape(6.dp)
    Text(
        text = label,
        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim, fontWeight = FontWeight.Bold),
        modifier = Modifier
            .testTag(tag)
            .clip(chipShape)
            .background(CompanionColor.VoidSurface)
            .pointerInput(onClick) { detectTapGestures { onClick() } }
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

private object MarkdownVisual : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        return TransformedText(annotateMarkdown(text.text), OffsetMapping.Identity)
    }
}

internal fun annotateMarkdown(text: String): AnnotatedString {
    val builder = AnnotatedString.Builder(text)
    val occupied = BooleanArray(text.length)
    fun take(regex: Regex, inner: SpanStyle, marker: Int) {
        regex.findAll(text).forEach { match ->
            val range = match.range
            if (range.any { occupied.getOrElse(it) { true } }) return@forEach
            range.forEach { occupied[it] = true }
            val innerStart = range.first + marker
            val innerEnd = range.last + 1 - marker
            if (innerStart < innerEnd) builder.addStyle(inner, innerStart, innerEnd)
            builder.addStyle(SpanStyle(color = CompanionColor.TextMute), range.first, innerStart)
            builder.addStyle(SpanStyle(color = CompanionColor.TextMute), innerEnd, range.last + 1)
        }
    }
    take(Regex("`([^`]+)`"), SpanStyle(fontFamily = FontFamily.Monospace, color = CompanionColor.Signal), 1)
    take(Regex("""\*\*(.+?)\*\*"""), SpanStyle(fontWeight = FontWeight.Bold), 2)
    take(
        Regex("""(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)"""),
        SpanStyle(fontStyle = FontStyle.Italic),
        1,
    )
    return builder.toAnnotatedString()
}
