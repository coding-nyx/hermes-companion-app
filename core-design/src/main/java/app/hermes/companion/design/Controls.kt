package app.hermes.companion.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Visual weight of an [ActionButton]. One accent, no fills except the primary. */
enum class ActionKind { PRIMARY, GHOST, DANGER }

/**
 * The one button. `[LABEL]` mono, hairline border, optional busy state that dims it and swaps the
 * label so a tap in flight is visible without a spinner. Disabled buttons drop to mute.
 */
@Composable
fun ActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ActionKind = ActionKind.GHOST,
    enabled: Boolean = true,
    busy: Boolean = false,
    busyLabel: String = "…",
) {
    val active = enabled && !busy
    val accent = when (kind) {
        ActionKind.PRIMARY, ActionKind.GHOST -> CompanionColor.Signal
        ActionKind.DANGER -> CompanionColor.Danger
    }
    val fg = if (active) accent else CompanionColor.TextMute
    val border = if (active) accent else CompanionColor.Line
    val bg = if (kind == ActionKind.PRIMARY && active) CompanionColor.SignalDim else Color.Transparent
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .border(CompanionSpace.Hairline, border)
            .background(bg)
            .clickable(enabled = active, onClick = onClick)
            .padding(horizontal = CompanionSpace.Lg, vertical = 10.dp),
    ) {
        if (busy) {
            SignalCursor()
            Spacer(Modifier.width(CompanionSpace.Sm))
        }
        Text(
            text = if (busy) busyLabel else label,
            style = CompanionType.MonoSmall.copy(color = fg),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** `TITLE ……… trailing` row that opens every section on the tab screens. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = CompanionSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = CompanionType.MonoSmall.copy(color = CompanionColor.Signal),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

/** `label   value` on one mono line; value takes the brighter colour. */
@Composable
fun KeyValueRow(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = CompanionColor.Text) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = CompanionSpace.Xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CompanionType.MonoSmall,
            maxLines = 1,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = CompanionType.Mono.copy(color = valueColor),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Tappable `LABEL  on/off` line with a small state box; the box is the only colour change. */
@Composable
fun ToggleRow(
    label: String,
    on: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = CompanionSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = CompanionType.Mono.copy(color = if (on) CompanionColor.Text else CompanionColor.TextDim),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!hint.isNullOrBlank()) {
                Text(text = hint, style = CompanionType.MonoSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(CompanionSpace.Md))
        Text(
            text = if (on) "■ ON" else "□ OFF",
            style = CompanionType.MonoSmall.copy(color = if (on) CompanionColor.Signal else CompanionColor.TextMute),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/**
 * Error / warning banner under the header. One line, mono, optional DISMISS.
 * Every tab that has no error slot of its own renders host failures here instead of swallowing them.
 */
@Composable
fun StatusStrip(
    text: String,
    modifier: Modifier = Modifier,
    tone: Color = CompanionColor.Danger,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(CompanionColor.VoidElevated)
            .padding(horizontal = CompanionSpace.Lg, vertical = CompanionSpace.Sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompanionSpace.Md),
    ) {
        Text(
            text = text,
            style = CompanionType.MonoSmall.copy(color = tone),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = CompanionType.MonoSmall.copy(color = CompanionColor.TextDim),
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(CompanionSpace.Xs),
            )
        }
    }
}

/**
 * Full-body pane while the operator connection is being rebuilt (host switch, reconnect with creds).
 * Replaces the stale tab content so the previous host's threads are never shown under a new host name.
 */
@Composable
fun SwitchingPane(
    hostName: String,
    origin: String,
    modifier: Modifier = Modifier,
    label: String = "SWITCHING HOST",
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CompanionColor.Void)
            .testTag("shell.switching"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RobotMark(size = 44.dp, awake = true)
        Spacer(Modifier.height(CompanionSpace.Lg))
        Text(
            text = hostName.ifBlank { origin.substringAfter("://") },
            style = CompanionType.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        FetchPane(
            label = label,
            hint = "// probe · auth · profiles · threads",
            scanning = true,
        )
    }
}

/**
 * The companion's face: rounded head, two signal eyes, antenna. Same geometry as the launcher icon
 * so the connect and boot screens carry the identity. [awake] lights the eyes; asleep is dim.
 */
@Composable
fun RobotMark(modifier: Modifier = Modifier, size: Dp = 56.dp, awake: Boolean = true, color: Color = CompanionColor.Signal) {
    val eye = if (awake) color else CompanionColor.TextMute
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.045f)
        // Antenna
        drawLine(color, Offset(w * 0.5f, h * 0.06f), Offset(w * 0.5f, h * 0.2f), strokeWidth = stroke.width)
        drawCircle(color, radius = w * 0.045f, center = Offset(w * 0.5f, h * 0.06f))
        // Head
        drawRoundRect(
            color = color,
            topLeft = Offset(w * 0.14f, h * 0.2f),
            size = Size(w * 0.72f, h * 0.66f),
            cornerRadius = CornerRadius(w * 0.12f),
            style = stroke,
        )
        // Ears
        drawRect(color, Offset(w * 0.04f, h * 0.44f), Size(w * 0.1f, h * 0.18f), style = stroke)
        drawRect(color, Offset(w * 0.86f, h * 0.44f), Size(w * 0.1f, h * 0.18f), style = stroke)
        // Eyes
        drawRoundRect(eye, Offset(w * 0.28f, h * 0.4f), Size(w * 0.14f, h * 0.16f), CornerRadius(w * 0.02f))
        drawRoundRect(eye, Offset(w * 0.58f, h * 0.4f), Size(w * 0.14f, h * 0.16f), CornerRadius(w * 0.02f))
        // Mouth
        drawLine(color, Offset(w * 0.34f, h * 0.7f), Offset(w * 0.66f, h * 0.7f), strokeWidth = stroke.width)
        drawLine(color, Offset(w * 0.44f, h * 0.7f), Offset(w * 0.44f, h * 0.76f), strokeWidth = stroke.width)
        drawLine(color, Offset(w * 0.56f, h * 0.7f), Offset(w * 0.56f, h * 0.76f), strokeWidth = stroke.width)
    }
}
