package app.hermes.companion.chat

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.hermes.companion.design.CompanionColor
import app.hermes.companion.design.CompanionSpace

internal enum class ComposerIcon { MICROPHONE, SEND, STEER, STOP, SETTINGS, REASONING, SPEAKER, COPY, ADD, COMMANDS, CODE, LIST, EXPAND }

/** Fixed touch targets keep the message field wide and independent of action labels. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ComposerButton(
    label: String,
    tag: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    foreground: Color = CompanionColor.Signal,
    background: Color = Color.Transparent,
    border: Color = Color.Transparent,
    content: @Composable (Color) -> Unit,
) {
    val tint = if (enabled) foreground else CompanionColor.TextMute
    Box(
        Modifier.size(48.dp)
            .testTag(tag)
            .clip(CircleShape)
            .background(background)

            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick, onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) "Start voice conversation" else null)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { content(tint) }
}

@Composable
internal fun ComposerActionIcon(icon: ComposerIcon, tint: Color, busy: Boolean = false) {
    if (busy) {
        CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
        return
    }
    Canvas(Modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        fun point(x: Float, y: Float) = Offset(x * unit, y * unit)
        val stroke = Stroke(width = 2f * unit, cap = StrokeCap.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(tint, point(x1, y1), point(x2, y2), strokeWidth = stroke.width, cap = StrokeCap.Round)
        when (icon) {
            ComposerIcon.EXPAND -> { line(4f, 9f, 4f, 4f); line(4f, 4f, 9f, 4f); line(15f, 4f, 20f, 4f); line(20f, 4f, 20f, 9f); line(4f, 15f, 4f, 20f); line(4f, 20f, 9f, 20f); line(15f, 20f, 20f, 20f); line(20f, 20f, 20f, 15f) }
            ComposerIcon.ADD -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            ComposerIcon.COMMANDS -> line(17f, 4f, 7f, 20f)
            ComposerIcon.CODE -> { line(8f, 6f, 3f, 12f); line(3f, 12f, 8f, 18f); line(16f, 6f, 21f, 12f); line(21f, 12f, 16f, 18f) }
            ComposerIcon.LIST -> { listOf(6f, 12f, 18f).forEach { y -> drawCircle(tint, 1.2f * unit, point(4f, y)); line(9f, y, 21f, y) } }
            ComposerIcon.COPY -> {
                drawRoundRect(tint, topLeft = point(8f, 8f), size = Size(12f * unit, 13f * unit), cornerRadius = CornerRadius(2f * unit), style = stroke)
                line(5f, 16f, 4f, 16f); line(4f, 16f, 4f, 4f); line(4f, 4f, 16f, 4f)
            }
            ComposerIcon.SPEAKER -> {
                drawPath(Path().apply { moveTo(3f * unit, 9f * unit); lineTo(7f * unit, 9f * unit); lineTo(12f * unit, 5f * unit); lineTo(12f * unit, 19f * unit); lineTo(7f * unit, 15f * unit); lineTo(3f * unit, 15f * unit); close() }, tint, style = stroke)
                drawArc(tint, -45f, 90f, false, point(11f, 6f), Size(10f * unit, 12f * unit), style = stroke)
            }
            ComposerIcon.REASONING -> {
                drawCircle(tint, 6f * unit, point(12f, 9f), style = stroke)
                line(9f, 15f, 9f, 18f); line(15f, 15f, 15f, 18f)
                line(9f, 18f, 15f, 18f); line(10f, 21f, 14f, 21f)
            }
            ComposerIcon.SETTINGS -> {
                line(3f, 7f, 21f, 7f)
                line(3f, 17f, 21f, 17f)
                drawCircle(tint, 3f * unit, point(8f, 7f))
                drawCircle(tint, 3f * unit, point(16f, 17f))
            }
            ComposerIcon.MICROPHONE -> {
                drawRoundRect(tint, topLeft = point(9f, 3f), size = Size(6f * unit, 12f * unit),
                    cornerRadius = CornerRadius(3f * unit), style = stroke)
                drawPath(Path().apply {
                    moveTo(6f * unit, 11f * unit)
                    cubicTo(6f * unit, 21f * unit, 18f * unit, 21f * unit, 18f * unit, 11f * unit)
                }, tint, style = stroke)
                line(12f, 19f, 12f, 22f)
                line(9f, 22f, 15f, 22f)
            }
            ComposerIcon.SEND -> {
                line(12f, 20f, 12f, 4f)
                line(5f, 11f, 12f, 4f)
                line(12f, 4f, 19f, 11f)
            }
            ComposerIcon.STEER -> {
                drawPath(Path().apply {
                    moveTo(5f * unit, 20f * unit)
                    lineTo(5f * unit, 12f * unit)
                    quadraticBezierTo(5f * unit, 7f * unit, 10f * unit, 7f * unit)
                    lineTo(20f * unit, 7f * unit)
                }, tint, style = stroke)
                line(15f, 2f, 20f, 7f)
                line(20f, 7f, 15f, 12f)
            }
            ComposerIcon.STOP -> drawRoundRect(tint, topLeft = point(6f, 6f),
                size = Size(12f * unit, 12f * unit), cornerRadius = CornerRadius(2f * unit))
        }
    }
}
