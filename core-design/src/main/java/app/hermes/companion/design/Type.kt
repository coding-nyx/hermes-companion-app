package app.hermes.companion.design

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** One sans family for the interface; monospace is reserved for code and exact machine values. */
object CompanionType {
    val Display get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp, color = CompanionColor.Text)
    val Title get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 18.sp, lineHeight = 24.sp, color = CompanionColor.Text)
    val Body get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, color = CompanionColor.Text)
    val RowTitle get() = Body.copy(fontWeight = FontWeight.Medium)
    val Label get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, color = CompanionColor.Text)
    val Caption get() = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 18.sp, color = CompanionColor.TextDim)
    val Mono get() = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal,
        fontSize = 13.sp, lineHeight = 20.sp, color = CompanionColor.Text)
    /** Source compatibility for diagnostic tools; UI labels use Caption. */
    val MonoSmall get() = Mono.copy(color = CompanionColor.TextDim)
}
