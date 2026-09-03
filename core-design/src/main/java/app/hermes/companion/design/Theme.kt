package app.hermes.companion.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = CompanionColor.Signal,
    onPrimary = CompanionColor.Void,
    background = CompanionColor.Void,
    onBackground = CompanionColor.Text,
    surface = CompanionColor.Void,
    onSurface = CompanionColor.Text,
    surfaceVariant = CompanionColor.VoidElevated,
    outline = CompanionColor.Line,
    error = CompanionColor.Danger,
)

val LocalCompanionColors = staticCompositionLocalOf { CompanionColor }

@Composable
fun CompanionTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCompanionColors provides CompanionColor) {
        MaterialTheme(
            colorScheme = Scheme.copy(scrim = Color.Transparent),
            content = content,
        )
    }
}
