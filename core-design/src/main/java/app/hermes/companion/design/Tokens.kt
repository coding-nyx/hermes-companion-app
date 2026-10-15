package app.hermes.companion.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Grouped: open reading surfaces, bounded settings, and explicit semantic contrast. */
object CompanionColor {
    // Read the system palette at use time. Canvas/ANSI helpers also consume these tokens
    // outside composition; no process-global mutable theme is shared between app windows.
    internal val dark: Boolean get() = android.content.res.Resources.getSystem().configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_NO
    private fun role(light: Long, dark: Long) = Color(if (this.dark) dark else light)
    val Void get() = role(0xFFF7F8F6, 0xFF101613)
    val VoidElevated get() = role(0xFFFFFFFF, 0xFF1A231E)
    val VoidSurface get() = role(0xFFEDF1EC, 0xFF212D25)
    val CardBorder get() = role(0xFFD9E1D9, 0xFF34463A)
    val BubbleUser get() = VoidSurface
    val BubbleAssistant get() = Void
    val Line get() = CardBorder
    val LineStrong get() = role(0xFFC5D0C6, 0xFF40564A)
    val Text get() = role(0xFF19291F, 0xFFEFF4EF)
    val TextDim get() = role(0xFF5A695F, 0xFFA6B6AC)
    val TextMute get() = role(0xFF5A695F, 0xFF95A79C)
    val Signal get() = role(0xFF256C48, 0xFFA3DCB9)
    val SignalDim get() = role(0xFFE8F0E7, 0xFF243A2B)
    val SignalSubtle get() = Signal.copy(alpha = .12f)
    val SignalGlow get() = Signal.copy(alpha = .2f)
    val Warn get() = role(0xFF775218, 0xFFE2C48B)
    val Danger get() = role(0xFFAD3839, 0xFFF0AAA5)
}

object CompanionSpace {
    val Hairline: Dp = 1.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 24.dp
    val Xxl = 32.dp
    val Page = 24.dp
    val Touch = 48.dp
    val RadiusChrome = 8.dp
    val RadiusChip = 6.dp
    val RadiusSmall = 8.dp
    val RadiusCard = 16.dp
    val RadiusBubble = 16.dp
    val RadiusPill = 24.dp
}

object CompanionMotion {
    const val SnapMs = 120
    const val CrossfadeMs = 180
    const val BlinkMs = 420
    const val ScanMs = 1400
}
