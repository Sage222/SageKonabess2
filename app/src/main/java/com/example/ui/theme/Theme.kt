package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HardwareTunerColorScheme = darkColorScheme(
    primary = TelemetryCyan,
    onPrimary = Color(0xFF00262C),
    primaryContainer = TelemetryCyanDim,
    onPrimaryContainer = TelemetryCyan,
    secondary = UndervoltEmerald,
    onSecondary = Color(0xFF002914),
    secondaryContainer = UndervoltEmeraldDim,
    onSecondaryContainer = UndervoltEmerald,
    tertiary = NominalAmber,
    onTertiary = Color(0xFF2E2000),
    tertiaryContainer = NominalAmberDim,
    onTertiaryContainer = NominalAmber,
    error = TurboCoral,
    onError = Color(0xFF330004),
    errorContainer = TurboCoralDim,
    onErrorContainer = TurboCoral,
    background = ObsidianBackground,
    onBackground = TextPrimary,
    surface = SlateSurface,
    onSurface = TextPrimary,
    surfaceVariant = SlateElevated,
    onSurfaceVariant = TextSecondary,
    outline = BorderHairline,
    outlineVariant = SlateCardHighlight
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = HardwareTunerColorScheme,
        typography = Typography,
        content = content
    )
}
