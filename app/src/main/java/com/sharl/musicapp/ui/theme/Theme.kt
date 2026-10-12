package com.sharl.musicapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun MusicAppTheme(
    accent: Color = DefaultAccent,
    content: @Composable () -> Unit
) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color.Black,
        secondary = accent,
        background = DarkBackground,
        onBackground = DarkOnBackground,
        surface = DarkSurface,
        onSurface = DarkOnBackground,
        surfaceVariant = DarkSurfaceVariant,
        onSurfaceVariant = DarkOnSurfaceVariant
    )
    MaterialTheme(colorScheme = scheme, typography = Typography, content = content)
}
