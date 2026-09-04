package app.hermes.companion.chat

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
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
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) {
        if (value != field.text) field = TextFieldValue(value, TextRange(value.length))
    }
    fun format(transform: (MarkdownWrap.Edit) -> MarkdownWrap.Edit) {
        val out = transform(MarkdownWrap.Edit(field.text, field.selection.start, field.selection.end))
        field = TextFieldValue(out.text, TextRange(out.selectionStart, out.selectionEnd))
        onValueChange(out.text)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(CompanionSpace.Hairline, CompanionColor.LineStrong),
    ) {
        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
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
                .heightIn(min = 56.dp, max = 140.dp)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .testTag("chat.draft"),
            textStyle = CompanionType.Body.copy(color = CompanionColor.Text),
            cursorBrush = SolidColor(CompanionColor.Signal),
            singleLine = false,
            minLines = 2,
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
                        text = "message",
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
    Text(
        text = label,
        style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
        modifier = Modifier
            .testTag(tag)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .pointerInput(onClick) { detectTapGestures { onClick() } },
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
