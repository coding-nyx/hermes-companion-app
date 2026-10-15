package app.hermes.companion.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf

private fun companionScheme() = (if (CompanionColor.dark) darkColorScheme() else androidx.compose.material3.lightColorScheme()).copy(
    primary = CompanionColor.Signal, onPrimary = CompanionColor.Void,
    primaryContainer = CompanionColor.SignalDim, onPrimaryContainer = CompanionColor.Text,
    secondary = CompanionColor.Signal, onSecondary = CompanionColor.Void,
    secondaryContainer = CompanionColor.SignalDim, onSecondaryContainer = CompanionColor.Text,
    tertiary = CompanionColor.Signal, onTertiary = CompanionColor.Void,
    background = CompanionColor.Void, onBackground = CompanionColor.Text,
    surface = CompanionColor.Void, onSurface = CompanionColor.Text,
    surfaceVariant = CompanionColor.VoidElevated, onSurfaceVariant = CompanionColor.TextDim,
    surfaceContainer = CompanionColor.VoidElevated,
    surfaceContainerLow = CompanionColor.VoidElevated,
    surfaceContainerHigh = CompanionColor.VoidElevated,
    surfaceContainerHighest = CompanionColor.VoidSurface,
    outline = CompanionColor.LineStrong, outlineVariant = CompanionColor.Line,
    error = CompanionColor.Danger, onError = CompanionColor.Void,
    scrim = androidx.compose.ui.graphics.Color.Black,
)
private fun companionTypography() = Typography(
    displayLarge = CompanionType.Display.copy(color = androidx.compose.ui.graphics.Color.Unspecified), displayMedium = CompanionType.Display.copy(color = androidx.compose.ui.graphics.Color.Unspecified), displaySmall = CompanionType.Display.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    headlineLarge = CompanionType.Display.copy(color = androidx.compose.ui.graphics.Color.Unspecified), headlineMedium = CompanionType.Display.copy(color = androidx.compose.ui.graphics.Color.Unspecified), headlineSmall = CompanionType.Title.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    titleLarge = CompanionType.Title.copy(color = androidx.compose.ui.graphics.Color.Unspecified), titleMedium = CompanionType.Body.copy(color = androidx.compose.ui.graphics.Color.Unspecified, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
    titleSmall = CompanionType.Label.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    bodyLarge = CompanionType.Body.copy(color = androidx.compose.ui.graphics.Color.Unspecified), bodyMedium = CompanionType.Body.copy(color = androidx.compose.ui.graphics.Color.Unspecified), bodySmall = CompanionType.Caption.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
    labelLarge = CompanionType.Label.copy(color = androidx.compose.ui.graphics.Color.Unspecified), labelMedium = CompanionType.Caption.copy(color = androidx.compose.ui.graphics.Color.Unspecified), labelSmall = CompanionType.Caption.copy(color = androidx.compose.ui.graphics.Color.Unspecified),
)
val LocalCompanionColors = staticCompositionLocalOf { CompanionColor }

@Composable
fun CompanionTheme(content: @Composable () -> Unit) {
    // Subscribe to configuration so the resource-backed palette and text styles refresh together.
    androidx.compose.ui.platform.LocalConfiguration.current
    CompositionLocalProvider(
        LocalCompanionColors provides CompanionColor,
        androidx.compose.material3.LocalContentColor provides CompanionColor.Text,
    ) {
        MaterialTheme(colorScheme = companionScheme(), typography = companionTypography(),
            shapes = Shapes(extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(24.dp)),
            content = content)
    }
}
