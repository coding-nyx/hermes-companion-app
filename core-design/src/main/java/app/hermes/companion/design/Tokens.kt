package app.hermes.companion.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Void, signal, type. Dark only. One accent. No elevation. */
object CompanionColor {
    val Void = Color(0xFF07080A)
    val VoidElevated = Color(0xFF0E1014)
    val VoidSurface = Color(0xFF141720)
    val CardBorder = Color(0xFF1E2430)
    val BubbleUser = Color(0xFF162332)
    val BubbleAssistant = Color(0xFF10151E)
    val Line = Color(0xFF1A1E26)
    val LineStrong = Color(0xFF2A3140)
    val Text = Color(0xFFE6EDF3)
    val TextDim = Color(0xFF8B96A8)
    val TextMute = Color(0xFF5C6570)
    val Signal = Color(0xFF00E5C3)
    val SignalDim = Color(0xFF0A3D36)
    val SignalSubtle = Color(0x1F00E5C3)
    val SignalGlow = Color(0x3300E5C3)
    val Warn = Color(0xFFF5A524)
    val Danger = Color(0xFFFF4D6A)
}

object CompanionSpace {
    val Hairline: Dp = 1.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 40.dp
    val RadiusChrome = 8.dp
    val RadiusChip = 6.dp
    val RadiusSmall = 8.dp
    val RadiusCard = 12.dp
    val RadiusBubble = 16.dp
    val RadiusPill = 24.dp
}

object CompanionMotion {
    const val SnapMs = 120
    const val CrossfadeMs = 180
    const val BlinkMs = 420
    const val ScanMs = 1400
}
