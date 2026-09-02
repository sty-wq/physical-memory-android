package dev.local.physicalmemory.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF28634F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE1F0E8),
    onPrimaryContainer = Color(0xFF143E2E),
    background = Color(0xFFF8F9F6),
    surface = Color(0xFFF8F9F6),
)

@Composable
fun MemoryTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else LightColors, content = content)
}
