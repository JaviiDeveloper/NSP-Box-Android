package com.nspbox.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NspColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = TextPrimary,
    primaryContainer = Panel,
    onPrimaryContainer = TextPrimary,
    secondary = Muted,
    onSecondary = TextPrimary,
    background = Background,
    onBackground = TextPrimary,
    surface = Panel,
    onSurface = TextPrimary,
    surfaceVariant = Field,
    onSurfaceVariant = Muted,
    outline = Border,
    error = ErrorRed,
    onError = TextPrimary
)

@Composable
fun NspBoxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NspColorScheme,
        typography = NspTypography,
        content = content
    )
}
