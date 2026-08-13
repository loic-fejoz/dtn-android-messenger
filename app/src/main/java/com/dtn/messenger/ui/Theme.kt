package com.dtn.messenger.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CharcoalBg = Color(0xFF0F0F12)
val GlassCardColor = Color(0x1F22222E)
val NeonCyan = Color(0xFF00E5FF)
val NeonPurple = Color(0xFF9D4EDD)
val GlowGreen = Color(0xFF00FF87)
val GlowRed = Color(0xFFFF0055)
val TextLight = Color(0xFFE2E2E9)
val TextGray = Color(0xFF8E8E9E)

private val DarkColorScheme =
    darkColorScheme(
        primary = NeonCyan,
        secondary = NeonPurple,
        tertiary = GlowGreen,
        background = CharcoalBg,
        surface = Color(0xFF1E1E24),
        onPrimary = Color.Black,
        onSecondary = Color.White,
        onBackground = TextLight,
        onSurface = TextLight,
        error = GlowRed,
    )

@Composable
fun DtnTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
