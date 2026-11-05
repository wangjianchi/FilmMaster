package com.filmmaster.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Amber = Color(0xFFE0A458)
private val Charcoal = Color(0xFF141414)
private val Paper = Color(0xFFF3EDE3)

private val FilmMasterColors = darkColorScheme(
    primary = Amber,
    onPrimary = Charcoal,
    secondary = Color(0xFF7FA6A0),
    background = Charcoal,
    onBackground = Paper,
    surface = Color(0xFF1E1E1E),
    onSurface = Paper
)

@Composable
fun FilmMasterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FilmMasterColors, content = content)
}
