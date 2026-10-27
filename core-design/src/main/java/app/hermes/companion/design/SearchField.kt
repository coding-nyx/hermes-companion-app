package app.hermes.companion.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Search has one centered text line and one padding owner, independent of form-field chrome. */
@Composable
fun SearchField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 8.dp, bottomStart = 8.dp)
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(value, onValueChange, singleLine = true,
        textStyle = CompanionType.Label.copy(color = CompanionColor.Text, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), cursorBrush = SolidColor(CompanionColor.Signal),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide(); focus.clearFocus() }),
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp).onFocusChanged { focused = it.isFocused }.clip(shape)
            .background(CompanionColor.VoidElevated).border(if (focused) 2.dp else 1.dp, if (focused) CompanionColor.Signal else CompanionColor.LineStrong, shape).semantics { contentDescription = placeholder },
        decorationBox = { input ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 10.dp, end = if (value.isEmpty()) 10.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.size(18.dp)) {
                    val unit = size.width / 24f
                    drawCircle(CompanionColor.TextDim, 6.5f * unit, Offset(10f * unit, 10f * unit), style = Stroke(1.8f * unit))
                    drawLine(CompanionColor.TextDim, Offset(15f * unit, 15f * unit), Offset(21f * unit, 21f * unit), 1.8f * unit, StrokeCap.Round)
                }
                Box(Modifier.weight(1f).padding(vertical = 6.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, style = CompanionType.Label.copy(color = CompanionColor.TextMute, fontWeight = androidx.compose.ui.text.font.FontWeight.Normal), maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    input()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Clear search" }) {
                    ControlIcon(ControlGlyph.CLOSE)
                }
            }
        })
}
