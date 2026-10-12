package com.sharl.musicapp.ui.theme

import androidx.compose.ui.graphics.Color

// Base surfaces (dark grey)
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkSurfaceVariant = Color(0xFF2A2A2A)
val DarkOnBackground = Color(0xFFE6E6E6)
val DarkOnSurfaceVariant = Color(0xFFB0B0B0)

// Accent palette (used by the picker)
data class AccentOption(val name: String, val color: Color)

val AccentOptions = listOf(
    AccentOption("Dodger Blue", Color(0xFF1E90FF)),
    AccentOption("Sunset Orange", Color(0xFFFF6B35)),
    AccentOption("Forest Green", Color(0xFF2ECC71)),
    AccentOption("Hot Pink", Color(0xFFFF2D95)),
    AccentOption("Purple", Color(0xFF8B5CF6)),
    AccentOption("Amber", Color(0xFFFBBF24)),
    AccentOption("Crimson", Color(0xFFE11D48))
)

val DefaultAccent = AccentOptions[0].color
