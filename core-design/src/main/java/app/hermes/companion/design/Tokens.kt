package app.hermes.companion.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Neon signal on ink: cyberpunk chrome, quiet reading surfaces, semantic contrast. */
object CompanionColor {
    // Read the system palette at use time. Canvas/ANSI helpers also consume these tokens
    // outside composition; no process-global mutable theme is shared between app windows.
    internal val dark: Boolean get() = android.content.res.Resources.getSystem().configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_NO
    private fun role(light: Long, dark: Long) = Color(if (this.dark) dark else light)
    val Void get() = role(0xFFF3F7FF, 0xFF080D17)
    val VoidElevated get() = role(0xFFFFFFFF, 0xFF101A2A)
    val VoidSurface get() = role(0xFFE5EEFA, 0xFF17243A)
    val CardBorder get() = role(0xFFCCD4E4, 0xFF2A3A54)
    val BubbleUser get() = VoidSurface
    val BubbleAssistant get() = Void
    val Line get() = CardBorder
    val LineStrong get() = role(0xFF71839F, 0xFF657D9F)
    val Text get() = role(0xFF14253D, 0xFFEDF6FF)
    val TextDim get() = role(0xFF4B5F7C, 0xFFA9B8CE)
    val TextMute get() = role(0xFF556780, 0xFF91A2BC)
    val Signal get() = role(0xFF00677D, 0xFF5BE7FF)
    val Accent get() = role(0xFF6744B8, 0xFFB9A0FF)
    val AccentDim get() = role(0xFFEEE8FF, 0xFF29233E)
    val SignalDim get() = role(0xFFDDF4FA, 0xFF0C2C3B)
    val SignalSubtle get() = Signal.copy(alpha = .12f)
    val SignalGlow get() = Signal.copy(alpha = .2f)
    val Warn get() = role(0xFF80520A, 0xFFFFC46B)
    val Danger get() = role(0xFFAA2948, 0xFFFF899F)
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
