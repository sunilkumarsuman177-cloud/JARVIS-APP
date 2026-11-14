package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisAmoledColorScheme = darkColorScheme(
    primary = JarvisCyan,
    onPrimary = AmoledBlack,
    primaryContainer = ObsidianCardElevated,
    onPrimaryContainer = JarvisCyan,
    secondary = JarvisBlue,
    onSecondary = TextPrimary,
    secondaryContainer = ObsidianCard,
    onSecondaryContainer = TextPrimary,
    tertiary = JarvisEmerald,
    onTertiary = AmoledBlack,
    background = AmoledBlack,
    onBackground = TextPrimary,
    surface = ObsidianSurface,
    onSurface = TextPrimary,
    surfaceVariant = ObsidianCard,
    onSurfaceVariant = TextSecondary,
    outline = CyanBorderDim,
    error = JarvisCoral
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = JarvisAmoledColorScheme,
        typography = Typography,
        content = content
    )
}
