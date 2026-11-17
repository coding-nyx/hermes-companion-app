package app.hermes.hands.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * iOS-inspired frosted glass color system for Hands.
 * Clean, professional slate & translucency rather than neon cyberpunk.
 */
data class HandsColors(
    val background: Color,
    val surfaceGlass: Color,
    val surfaceElevated: Color,
    val surfaceHighlight: Color,
    val strokeGlass: Color,
    val strokeStrong: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentGlow: Color,
    val success: Color,
    val successGlow: Color,
    val danger: Color,
    val dangerGlow: Color,
    val warning: Color,
    val isDark: Boolean,
)

val DarkHandsColors = HandsColors(
    background = Color(0xFF0A0C10),
    surfaceGlass = Color(0xF0141822),        // Rich dark acrylic glass (~94% opacity)
    surfaceElevated = Color(0xF81A1F2D),     // Elevated modal/card glass (~97% opacity)
    surfaceHighlight = Color(0x33FFFFFF),    // Specular highlight
    strokeGlass = Color(0x2EFFFFFF),         // Crisp chamfered edge stroke
    strokeStrong = Color(0x59FFFFFF),        // Strong edge stroke
    textPrimary = Color(0xFFF5F5F7),         // Apple Off-White
    textSecondary = Color(0xFFA1A1A6),       // Apple System Gray
    textTertiary = Color(0xFF6E6E73),        // Apple Muted Gray
    accent = Color(0xFF0A84FF),              // iOS Dark Mode System Blue
    accentGlow = Color(0x330A84FF),
    success = Color(0xFF30D158),             // iOS System Green
    successGlow = Color(0x3330D158),
    danger = Color(0xFFFF453A),              // iOS System Red
    dangerGlow = Color(0x33FF453A),
    warning = Color(0xFFFF9F0A),             // iOS System Orange
    isDark = true,
)

val LightHandsColors = HandsColors(
    background = Color(0xFFF2F4F8),          // iOS System Grouped Background
    surfaceGlass = Color(0xF2FFFFFF),        // Rich frosted light glass (~95% opacity)
    surfaceElevated = Color(0xFAFFFFFF),     // Elevated modal/card glass (~98% opacity)
    surfaceHighlight = Color(0xFFFFFFFF),
    strokeGlass = Color(0x1F000000),         // ~12% dark outline
    strokeStrong = Color(0x33000000),
    textPrimary = Color(0xFF1C1C1E),         // iOS Primary Text
    textSecondary = Color(0xFF636366),       // iOS Secondary Text
    textTertiary = Color(0xFF8E8E93),        // iOS Tertiary Text
    accent = Color(0xFF007AFF),              // iOS Light Mode System Blue
    accentGlow = Color(0x26007AFF),
    success = Color(0xFF34C759),             // iOS Green
    successGlow = Color(0x2634C759),
    danger = Color(0xFFFF3B30),              // iOS Red
    dangerGlow = Color(0x26FF3B30),
    warning = Color(0xFFFF9500),
    isDark = false,
)

val LocalHandsColors = staticCompositionLocalOf { DarkHandsColors }

/** Clean, professional typography for Hands. */
object HandsType {
    val LargeTitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    )
    val Title = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 19.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    )
    val Subtitle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )
    val Body = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 19.sp,
    )
    val Caption = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    )
    val Label = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 18.sp,
    )
    val Mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )
    val MonoSmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

object HandsShapes {
    val Small = RoundedCornerShape(10.dp)
    val Medium = RoundedCornerShape(16.dp)
    val Large = RoundedCornerShape(22.dp)
    val Pill = RoundedCornerShape(100.dp)
}

@Composable
fun HandsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkHandsColors else LightHandsColors
    val m3Scheme = if (darkTheme) {
        darkColorScheme(
            background = colors.background,
            surface = colors.surfaceElevated,
            primary = colors.accent,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            background = colors.background,
            surface = colors.surfaceElevated,
            primary = colors.accent,
            error = colors.danger,
        )
    }

    CompositionLocalProvider(
        LocalHandsColors provides colors,
        LocalContentColor provides colors.textPrimary,
    ) {
        MaterialTheme(
            colorScheme = m3Scheme,
            content = content,
        )
    }
}

/** Accessor for HandsTheme.colors */
object HandsTheme {
    val colors: HandsColors
        @Composable
        get() = LocalHandsColors.current
    val type: HandsType
        get() = HandsType
    val shapes: HandsShapes
        get() = HandsShapes
}
