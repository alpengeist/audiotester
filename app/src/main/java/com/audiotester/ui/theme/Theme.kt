package com.audiotester.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Copper,
    onPrimary = Slate,
    secondary = Mist,
    background = Slate,
    onBackground = Sand,
    surface = ColorDeepSurface,
    onSurface = Sand,
    surfaceContainerHigh = ColorPanel,
)

@Composable
fun AudiotesterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = AppTypography,
        content = content,
    )
}
