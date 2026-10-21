package app.hermes.companion.chat

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkdownComposer(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    onSend: () -> Unit = {},
    onOpenModelSettings: (() -> Unit)? = null,
    modelSettingsEnabled: Boolean = true,
    leadingControls: (@Composable () -> Unit)? = null,
    trailingControls: (@Composable () -> Unit)? = null,
    compactLayout: Boolean = false,
    onOpenReasoningSettings: (() -> Unit)? = onOpenModelSettings,
    onExpand: (() -> Unit)? = null,
    expanded: Boolean = false,
    tagPrefix: String = "chat",
) {
    var field by remember { mutableStateOf(TextFieldValue(value)) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(value) {
        if (value != field.text) field = TextFieldValue(value, TextRange(value.length))
    }
    fun format(transform: (MarkdownWrap.Edit) -> MarkdownWrap.Edit) {
        val out = transform(MarkdownWrap.Edit(field.text, field.selection.start, field.selection.end))
        field = TextFieldValue(out.text, TextRange(out.selectionStart, out.selectionEnd))
        onValueChange(out.text)
    }
    val shape = CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(CompanionColor.VoidSurface)
        .border(1.dp, CompanionColor.LineStrong, shape).padding(4.dp)) {
        if (!compactLayout) FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            FormatChip("B", "$tagPrefix.fmt.bold") { format(MarkdownWrap::bold) }
            FormatChip("I", "$tagPrefix.fmt.italic") { format(MarkdownWrap::italic) }
            FormatChip("`", "$tagPrefix.fmt.code") { format(MarkdownWrap::code) }
            FormatChip("-", "$tagPrefix.fmt.list") { format(MarkdownWrap::bullet) }
            if (onExpand != null) IconButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); onExpand() },
                modifier = Modifier.size(48.dp).testTag("chat.expand").semantics { contentDescription = "Expand draft" }) {
                ComposerActionIcon(ComposerIcon.EXPAND, CompanionColor.TextDim)
            }
            if (onOpenModelSettings != null) {
                TextButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); onOpenModelSettings() },
                    enabled = modelSettingsEnabled, modifier = Modifier.heightIn(min = 48.dp).testTag("chat.model.settings")
                        .semantics { contentDescription = "Model settings for this conversation" }) {
                    ComposerActionIcon(ComposerIcon.SETTINGS, if (modelSettingsEnabled) CompanionColor.Signal else CompanionColor.TextMute)
                    Spacer(Modifier.width(6.dp))
                    Text("Model", style = CompanionType.Label)
                }
                if (onOpenReasoningSettings != null) TextButton(
                    onClick = { focusManager.clearFocus(); keyboard?.hide(); onOpenReasoningSettings() },
                    enabled = modelSettingsEnabled, modifier = Modifier.heightIn(min = 48.dp).testTag("chat.reasoning.settings")
                        .semantics { contentDescription = "Reasoning settings for this conversation" }) {
                    ComposerActionIcon(ComposerIcon.REASONING, if (modelSettingsEnabled) CompanionColor.Accent else CompanionColor.TextMute)
                    Spacer(Modifier.width(6.dp))
                    Text("Reasoning", style = CompanionType.Label)
                }
            }
        }
        if (!compactLayout) Hairline()
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (compactLayout) Column {
                Row {
                    FormatChip("B", "$tagPrefix.fmt.bold") { format(MarkdownWrap::bold) }
                    FormatChip("I", "$tagPrefix.fmt.italic") { format(MarkdownWrap::italic) }
                }
                Row {
                    FormatChip("`", "$tagPrefix.fmt.code") { format(MarkdownWrap::code) }
                    FormatChip("-", "$tagPrefix.fmt.list") { format(MarkdownWrap::bullet) }
                }
            }
            leadingControls?.invoke()
            Column {
            if (compactLayout && onOpenModelSettings != null) {
                IconButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); onOpenModelSettings() },
                    enabled = modelSettingsEnabled, modifier = Modifier.size(48.dp).testTag("chat.model.settings")
                        .semantics { contentDescription = "Model settings for this conversation" }) {
                    ComposerActionIcon(ComposerIcon.SETTINGS, CompanionColor.TextDim)
                }
                IconButton(onClick = { focusManager.clearFocus(); keyboard?.hide(); onOpenReasoningSettings?.invoke() },
                    enabled = modelSettingsEnabled, modifier = Modifier.size(48.dp).testTag("chat.reasoning.settings")
                        .semantics { contentDescription = "Reasoning settings for this conversation" }) {
                    ComposerActionIcon(ComposerIcon.REASONING, CompanionColor.TextDim)
                }
            }
            }
        BasicTextField(
            value = field,
            onValueChange = {
                field = it
                onValueChange(it.text)
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = if (expanded) 240.dp else 102.dp, max = if (expanded) 480.dp else 156.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp)
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
                .testTag("$tagPrefix.draft"),
            textStyle = CompanionType.Body.copy(color = CompanionColor.Text),
            cursorBrush = SolidColor(CompanionColor.Signal),
            singleLine = false,
            minLines = 1,
            maxLines = if (expanded) 30 else 8,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Default,
            ),
            visualTransformation = MarkdownVisual,
            decorationBox = { inner ->
                if (field.text.isEmpty()) {
                    Text(
                        text = "Message Hermes…",
                        style = CompanionType.Body.copy(color = CompanionColor.TextMute),
                    )
                }
                inner()
            },
        )
            trailingControls?.invoke()
        }
    }
}

@Composable
private fun FormatChip(label: String, tag: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag(tag).size(48.dp).semantics {
        contentDescription = when(label) { "B" -> "Bold"; "I" -> "Italic"; "`" -> "Inline code"; else -> "Bullet list" }
    }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
        when(label) {
            "`" -> ComposerActionIcon(ComposerIcon.CODE, CompanionColor.TextDim)
            "-" -> ComposerActionIcon(ComposerIcon.LIST, CompanionColor.TextDim)
            else -> Text(label, style = CompanionType.Label.copy(color = CompanionColor.TextDim,
                fontWeight = if (label == "B") FontWeight.Bold else FontWeight.Normal,
                fontStyle = if (label == "I") FontStyle.Italic else FontStyle.Normal))
        }
    }
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
