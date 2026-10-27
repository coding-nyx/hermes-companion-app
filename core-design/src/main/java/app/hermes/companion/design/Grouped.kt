package app.hermes.companion.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A titled group is the boundary between settings, never a card around a transcript. */
@Composable
fun GroupedSection(
    title: String,
    modifier: Modifier = Modifier,
    padded: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeader(title, trailing = trailing)
        GroupSurface(padded = padded, content = content)
    }
}

@Composable
fun GroupSurface(modifier: Modifier = Modifier, padded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val shape = androidx.compose.foundation.shape.CutCornerShape(topEnd = 12.dp, bottomStart = 12.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(CompanionColor.VoidElevated)
        .border(1.dp, CompanionColor.CardBorder, shape)
        .then(if (padded) Modifier.padding(12.dp) else Modifier),
        verticalArrangement = if (padded) Arrangement.spacedBy(12.dp) else Arrangement.Top,
        content = content)
}

/** Label and supporting text stay together. A long value moves below them at large text. */
@Composable
fun PreferenceRow(
    title: String,
    summary: String? = null,
    modifier: Modifier = Modifier,
    value: String? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    selected: Boolean = false,
) {
    val large = LocalDensity.current.fontScale >= 1.5f
    Column(Modifier.fillMaxWidth()) {
        Row(modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading?.invoke()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = CompanionType.RowTitle, color = if (enabled) CompanionColor.Text else CompanionColor.TextMute)
                if (!summary.isNullOrBlank()) Text(summary, style = CompanionType.Caption)
                if (large && !value.isNullOrBlank()) Text(value, style = CompanionType.Caption, color = if (selected) CompanionColor.Signal else CompanionColor.TextDim)
            }
            if (!large && !value.isNullOrBlank()) Text(value, style = CompanionType.Caption,
                color = if (selected) CompanionColor.Signal else CompanionColor.TextDim,
                modifier = Modifier.widthIn(max = 88.dp))
            if (onClick != null) ControlIcon(if (selected) ControlGlyph.CHECK else ControlGlyph.CHEVRON)
        }
        HorizontalDivider(color = CompanionColor.Line)
    }
}

@Composable
fun PreferenceToggle(title: String, on: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier,
    summary: String? = null, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth()) {
        ToggleRow(title, on, onToggle, hint = summary, enabled = enabled, modifier = modifier.padding(horizontal = 16.dp))
        HorizontalDivider(color = CompanionColor.Line)
    }
}

/** Sheet headings always reserve a real target for close/back, including large text. */
@Composable
fun SheetHeading(title: String, onClose: () -> Unit, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, closeModifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) IconButton(onClick = onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Back" }) { ControlIcon(ControlGlyph.BACK) }
        Text(title, style = CompanionType.Display, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose, modifier = closeModifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { contentDescription = "Close" }) {
            androidx.compose.foundation.layout.Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                ControlIcon(ControlGlyph.CLOSE)
            }
        }
    }
}
