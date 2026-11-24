package io.github.clement83.sillage.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Forest = Color(0xFF1F5134)
private val ForestLight = Color(0xFF8FD3A6)
private val Ember = Color(0xFFC2560C)
private val EmberLight = Color(0xFFFFB68A)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB7EFC8),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Ember,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDBC9),
    onSecondaryContainer = Color(0xFF331200),
    background = Color(0xFFF7F8F3),
    surface = Color(0xFFF7F8F3),
)

private val DarkColors = darkColorScheme(
    primary = ForestLight,
    onPrimary = Color(0xFF00391D),
    primaryContainer = Color(0xFF07522D),
    onPrimaryContainer = Color(0xFFB7EFC8),
    secondary = EmberLight,
    onSecondary = Color(0xFF552100),
    secondaryContainer = Color(0xFF783100),
    onSecondaryContainer = Color(0xFFFFDBC9),
    background = Color(0xFF111411),
    surface = Color(0xFF111411),
)

/** Recording and key-moment actions use the secondary ("ember") color so they stand out on the map. */
@Composable
fun SillageTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
