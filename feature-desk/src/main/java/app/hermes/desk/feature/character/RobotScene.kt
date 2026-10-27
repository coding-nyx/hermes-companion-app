package app.hermes.desk.feature.character

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.hermes.companion.VoiceStreamState
import app.hermes.desk.feature.DeskState
import kotlinx.coroutines.isActive
import kotlin.math.*

enum class RobotExpression { AVAILABLE, LISTENING, CONSIDERING, SPEAKING, DECISION, MUTED, OFFLINE }

fun robotExpression(state: DeskState): RobotExpression = when {
    !state.connected -> RobotExpression.OFFLINE
    state.pending != null || state.uncertain -> RobotExpression.DECISION
    state.voice == VoiceStreamState.SPEAKING -> RobotExpression.SPEAKING
    state.voice == VoiceStreamState.LISTENING -> RobotExpression.LISTENING
    state.voice == VoiceStreamState.MUTED -> RobotExpression.MUTED
    state.working || state.voice in setOf(VoiceStreamState.THINKING, VoiceStreamState.TRANSCRIBING) -> RobotExpression.CONSIDERING
    else -> RobotExpression.AVAILABLE
}

/** Full-body vector rig. Only the draw phase reads the animation clock. */
@Composable
fun RobotScene(state: DeskState, modifier: Modifier = Modifier) {
    val expression = robotExpression(state)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val clock = remember { mutableFloatStateOf(0f) }
    val tilt = animateFloatAsState(when (expression) { RobotExpression.CONSIDERING -> -5f; RobotExpression.LISTENING -> 2f; else -> 0f },
        tween(if (state.reducedMotion) 0 else 240), label = "Robot head tilt")
    LaunchedEffect(lifecycle, state.reducedMotion, expression) {
        clock.floatValue = 0f
        if (!state.reducedMotion) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var start = 0L
            var last = 0L
            while (isActive) withInfiniteAnimationFrameNanos { now ->
                if (start == 0L) start = now
                val interval = if (expression == RobotExpression.SPEAKING) 33_000_000 else 80_000_000
                if (now - last >= interval) {
                    clock.floatValue = (now - start) / 1_000_000_000f
                    last = now
                }
            }
        }
    }
    Canvas(modifier.semantics { contentDescription = "Desk robot: ${expression.name.lowercase()}" }) {
        val factor = min(size.width / 420f, size.height / 300f)
        translate((size.width - 420 * factor) / 2, (size.height - 300 * factor) / 2) {
            scale(factor, factor, Offset.Zero) { drawRobot(expression, clock.floatValue, state.reducedMotion, tilt.value) }
        }
    }
}

private val Accent = Color(0xFFB5DFCE)
private val Joint = Color(0xFF4C6561)
private fun DrawScope.shell(x: Float, y: Float, radius: Float = 100f) = Brush.radialGradient(
    listOf(Color(0xFFFFFCEC), Color(0xFF95B3A8)), Offset(x - radius * .3f, y - radius * .4f), radius)
private fun DrawScope.box(x: Float, y: Float, w: Float, h: Float, radius: Float, color: Color) =
    drawRoundRect(color, Offset(x, y), Size(w, h), CornerRadius(radius))
private fun DrawScope.box(x: Float, y: Float, w: Float, h: Float, radius: Float, brush: Brush) =
    drawRoundRect(brush, Offset(x, y), Size(w, h), CornerRadius(radius))

private fun DrawScope.drawRobot(expression: RobotExpression, time: Float, reduced: Boolean, tilt: Float) {
    val considering = expression == RobotExpression.CONSIDERING
    val speaking = expression == RobotExpression.SPEAKING
    val quiet = expression in setOf(RobotExpression.MUTED, RobotExpression.OFFLINE)
    val breathe = if (reduced || quiet) 0f else sin(time * 1.7f) * 1.1f
    val accent = when (expression) { RobotExpression.DECISION -> Color(0xFFF0CE95); RobotExpression.OFFLINE -> Color(0xFF9AAFA6); else -> Accent }
    drawOval(Color(0x25293832), Offset(124f, 259f), Size(172f, 13f))
    translate(0f, -breathe) {
        rotate(tilt, Offset(210f, 225f)) {
            box(177f, 234f, 18f, 22f, 8f, Joint); box(226f, 234f, 18f, 22f, 8f, Joint)
            box(163f, 250f, 40f, 17f, 8f, shell(178f, 252f, 45f)); box(217f, 250f, 40f, 17f, 8f, shell(230f, 252f, 45f))
            drawCircle(Joint, 12f, Offset(160f, 197f)); drawCircle(Joint, 12f, Offset(260f, 197f))
            box(169f, 173f, 82f, 71f, 26f, shell(195f, 191f, 90f))
            box(186f, 194f, 48f, 31f, 12f, Color(0xFFB3CBC0))
            box(200f, 203f, 20f, 5f, 3f, accent)
            box(197f, 229f, 26f, 3f, 1.5f, Color(0xFF6B8F84))
            box(198f, 161f, 24f, 19f, 8f, Joint)
            box(119f, 110f, 20f, 41f, 9f, shell(128f, 117f, 40f)); box(281f, 110f, 20f, 41f, 9f, shell(286f, 117f, 40f))
            val antenna = Path().apply { moveTo(210f, 78f); quadraticTo(207f + breathe, 61f, 220f + breathe, 49f) }
            drawPath(antenna, Color(0xFF92B6A7), style = Stroke(6f, cap = StrokeCap.Round))
            drawCircle(accent, 8f, Offset(220f + breathe, 49f))
            box(132f, 76f, 156f, 101f, 30f, shell(180f, 98f, 150f))
            box(142f, 87f, 136f, 77f, 25f, Brush.linearGradient(listOf(Color(0xFF314F4B), Color(0xFF0E2726))))
            val visor = Path().apply { addRoundRect(RoundRect(Rect(142f, 87f, 278f, 164f), CornerRadius(25f))) }
            clipPath(visor) {
                val reflection = Path().apply { moveTo(144f, 88f); lineTo(238f, 88f); lineTo(191f, 164f); lineTo(145f, 164f); close() }
                drawPath(reflection, Color(0x14DEFFF0))
            }
            val blink = !reduced && !quiet && time % 6.5f in 5.9f..6.04f
            val eyeHeight = if (quiet || blink) 5f else if (considering) 23f else 28f
            val gaze = if (considering) 4f else 0f
            for ((index, eye) in listOf(178f, 242f).withIndex()) {
                box(eye - 9 + gaze, 122f - eyeHeight / 2, 18f, eyeHeight, 8f, accent)
                if (!quiet && !blink) drawOval(Color(0xFFF1FFF6), Offset(eye - 5 + gaze, 112f), Size(4.6f, 6f))
                val brow = Path().apply { moveTo(eye - 9, 100f); quadraticTo(eye, if (considering && index == 1) 92f else 97f, eye + 8, 99f) }
                drawPath(brow, accent, style = Stroke(2.5f, cap = StrokeCap.Round))
            }
            if (speaking) {
                // A stylized speaking pose, bounded by actual playback state; no phoneme claim.
                val opening = if (reduced) 5f else 4f + abs(sin(time * 12f)) * 7f
                box(202f, 145f, 16f, opening, 4f, accent)
            } else {
                val mouth = Path().apply { moveTo(201f, 145f); quadraticTo(210f, if (quiet) 145f else 151f, 219f, 145f) }
                drawPath(mouth, accent, style = Stroke(2.6f, cap = StrokeCap.Round))
            }
            rotate(if (considering) -42f else -7f, Offset(157f, 198f)) {
                box(147f, 198f, 20f, 33f, 9f, shell(153f, 202f, 38f)); drawCircle(shell(153f, 224f, 26f), 12f, Offset(157f, 229f))
            }
            val gesture = if (speaking && !reduced) sin(time * 2.2f) * 7 else 0f
            rotate(10f + gesture, Offset(263f, 198f)) {
                box(253f, 198f, 20f, 33f, 9f, shell(259f, 202f, 38f)); drawCircle(shell(259f, 224f, 26f), 12f, Offset(263f, 229f))
            }
        }
    }
}
