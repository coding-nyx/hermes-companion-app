package app.hermes.companion.chat

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
    onEditingChange: (Boolean) -> Unit = {},
    placeholder: String = "Message…",
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
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
    var focused by remember { mutableStateOf(false) }
    var formatting by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(false) }
    var hadKeyboard by remember { mutableStateOf(false) }
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible && focused) hadKeyboard = true
        else if (!imeVisible && hadKeyboard && !expanded && !formatting && !controls) { hadKeyboard = false; focusManager.clearFocus() }
    }
    val editing = focused || expanded || formatting || controls
    LaunchedEffect(editing) { onEditingChange(editing) }
    val shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
    Column(modifier.fillMaxWidth().testTag("$tagPrefix.composer").clip(shape).background(CompanionColor.VoidSurface)
        .border(1.dp, if (editing) CompanionColor.Signal else CompanionColor.LineStrong, shape).padding(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = field,
                onValueChange = { field = it; onValueChange(it.text) },
                modifier = Modifier.weight(1f)
                    .heightIn(min = if (expanded) 144.dp else 48.dp, max = if (expanded) 480.dp else if (editing) 144.dp else androidx.compose.ui.unit.Dp.Infinity)
                    .onFocusChanged { focused = it.isFocused }
                    .onPreviewKeyEvent { ev ->
                        val enter = ev.key == Key.Enter || ev.key == Key.NumPadEnter
                        if (enter && ev.type == KeyEventType.KeyDown && (ev.isShiftPressed || ev.isCtrlPressed)) {
                            if (field.text.isNotBlank()) onSend()
                            true
                        } else false
                    }
                    .testTag("$tagPrefix.draft"),
                textStyle = CompanionType.Body.copy(color = CompanionColor.Text),
                cursorBrush = SolidColor(CompanionColor.Signal),
                singleLine = !editing,
                minLines = 1,
                maxLines = if (expanded) 30 else if (editing) 6 else 1,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text, imeAction = ImeAction.Default),
                visualTransformation = MarkdownVisual,
                decorationBox = { inner ->
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), contentAlignment = if (expanded) Alignment.TopStart else Alignment.CenterStart) {
                        if (field.text.isEmpty()) Text(placeholder, style = CompanionType.Body.copy(color = CompanionColor.TextMute), maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        inner()
                    }
                },
            )
            if (!editing) trailingControls?.invoke()
        }
        if (editing) {
            Hairline()
            Row(Modifier.fillMaxWidth().testTag("$tagPrefix.tools"),
                verticalAlignment = Alignment.CenterVertically) {
                leadingControls?.invoke()
                Box {
                    IconButton(onClick = { formatting = true }, modifier = Modifier.size(48.dp).testTag("$tagPrefix.format")
                        .semantics { contentDescription = "Text formatting" }) {
                        ComposerActionIcon(ComposerIcon.CODE, CompanionColor.TextDim)
                    }
                    DropdownMenu(formatting, { formatting = false }, containerColor = CompanionColor.VoidElevated) {
                        listOf("Bold" to "bold", "Italic" to "italic", "Inline code" to "code", "Bullet list" to "list").forEach { (label, key) ->
                            DropdownMenuItem(text = { Text(label) }, modifier = Modifier.testTag("$tagPrefix.fmt.$key"), onClick = {
                                formatting = false
                                format(when (key) { "bold" -> MarkdownWrap::bold; "italic" -> MarkdownWrap::italic; "code" -> MarkdownWrap::code; else -> MarkdownWrap::bullet })
                            })
                        }
                    }
                }
                if (onOpenModelSettings != null || onExpand != null) Box {
                    IconButton(onClick = { controls = true }, modifier = Modifier.size(48.dp).testTag("$tagPrefix.controls")
                        .semantics { contentDescription = "Conversation controls" }) {
                        ComposerActionIcon(ComposerIcon.SETTINGS, CompanionColor.TextDim)
                    }
                    DropdownMenu(controls, { controls = false }, containerColor = CompanionColor.VoidElevated) {
                        if (onOpenModelSettings != null) DropdownMenuItem(text = { Text("Model") },
                            enabled = modelSettingsEnabled, modifier = Modifier.testTag("$tagPrefix.model.settings"), onClick = {
                                controls = false; focusManager.clearFocus(); keyboard?.hide(); onOpenModelSettings()
                            })
                        if (onOpenReasoningSettings != null) DropdownMenuItem(text = { Text("Reasoning") },
                            enabled = modelSettingsEnabled, modifier = Modifier.testTag("$tagPrefix.reasoning.settings"), onClick = {
                                controls = false; focusManager.clearFocus(); keyboard?.hide(); onOpenReasoningSettings()
                            })
                        if (onExpand != null) DropdownMenuItem(text = { Text("Expand draft") },
                            modifier = Modifier.testTag("$tagPrefix.expand"), onClick = {
                                controls = false; focusManager.clearFocus(); keyboard?.hide(); onExpand()
                            })
                    }
                }
                Spacer(Modifier.weight(1f))
                trailingControls?.invoke()
            }
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
