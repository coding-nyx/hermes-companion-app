package app.hermes.companion.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Void, signal, type. Dark only. One accent. No elevation. */
object CompanionColor {
    val Void = Color(0xFF07080A)
    val VoidElevated = Color(0xFF0E1014)
    val Line = Color(0xFF1A1E26)
    val LineStrong = Color(0xFF2A3140)
    val Text = Color(0xFFE6EDF3)
    val TextDim = Color(0xFF8B96A8)
    val TextMute = Color(0xFF5C6570)
    val Signal = Color(0xFF00E5C3)
    val SignalDim = Color(0xFF0A3D36)
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
    val RadiusChrome = 0.dp
    val RadiusChip = 2.dp
}

object CompanionMotion {
    const val SnapMs = 120
    const val CrossfadeMs = 180
}
