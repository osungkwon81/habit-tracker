package com.habittracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme(
    primary = Teal600,
    secondary = Coral500,
    tertiary = Slate900,
    background = Sand100,
)

private val DarkColors = darkColorScheme(
    primary = Mint200,
    secondary = Coral500,
    tertiary = Sand100,
    background = Slate900,
)

@Composable
fun HabitTrackerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
