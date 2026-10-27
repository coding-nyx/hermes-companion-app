package app.hermes.companion.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

enum class ControlGlyph { BACK, SETTINGS, CLOSE, CHEVRON, CHECK, MORE, PLUS, REPLY }

/** Shared chrome icons use the same 24-unit geometry and stroke as the navigation dock. */
@Composable
fun ControlIcon(glyph: ControlGlyph, color: Color = CompanionColor.TextDim) {
    Canvas(Modifier.size(24.dp)) {
        val unit = size.minDimension / 24f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color,
            Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), 1.8f * unit, StrokeCap.Round)
        when (glyph) {
            ControlGlyph.BACK -> { line(20f, 12f, 4f, 12f); line(4f, 12f, 11f, 5f); line(4f, 12f, 11f, 19f) }
            ControlGlyph.REPLY -> { line(10f, 5f, 4f, 11f); line(4f, 11f, 10f, 17f); line(4f, 11f, 15f, 11f); line(15f, 11f, 20f, 16f); line(20f, 16f, 20f, 20f) }
            ControlGlyph.CLOSE -> { line(6f, 6f, 18f, 18f); line(6f, 18f, 18f, 6f) }
            ControlGlyph.CHEVRON -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            ControlGlyph.CHECK -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 7f) }
            ControlGlyph.MORE -> listOf(5f, 12f, 19f).forEach { drawCircle(color, 1.7f * unit, Offset(it * unit, 12f * unit)) }
            ControlGlyph.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            ControlGlyph.SETTINGS -> {
                line(4f, 7f, 20f, 7f); line(4f, 17f, 20f, 17f)
                drawCircle(color, 2.7f * unit, Offset(9f * unit, 7f * unit))
                drawCircle(color, 2.7f * unit, Offset(15f * unit, 17f * unit))
            }
        }
    }
}
