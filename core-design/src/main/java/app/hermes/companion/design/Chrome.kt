package app.hermes.companion.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = CompanionSpace.Hairline,
        color = CompanionColor.Line,
    )
}

@Composable
fun ProfileGlyph(
    code: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val border = if (selected) CompanionColor.Signal else CompanionColor.LineStrong
    val fg = if (selected) CompanionColor.Signal else CompanionColor.TextMute
    Box(
        modifier = modifier
            .size(36.dp)
            .border(CompanionSpace.Hairline, border)
            .background(CompanionColor.Void)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = code, style = CompanionType.Mono.copy(color = fg))
    }
}

@Composable
fun HudDot(label: String, on: Boolean, degraded: Boolean = false, modifier: Modifier = Modifier) {
    val color = when {
        degraded -> CompanionColor.Warn
        on -> CompanionColor.Signal
        else -> CompanionColor.TextMute
    }
    Text(text = label, style = CompanionType.MonoSmall.copy(color = color), modifier = modifier)
}

@Composable
fun MachineChip(label: String, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = CompanionType.MonoSmall.copy(color = CompanionColor.Void),
        modifier = modifier
            .background(CompanionColor.Signal)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
fun SignalRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = CompanionType.Mono)
        Spacer(Modifier.width(CompanionSpace.Sm))
        Text(text = value, style = CompanionType.Mono.copy(color = CompanionColor.Text))
    }
}
