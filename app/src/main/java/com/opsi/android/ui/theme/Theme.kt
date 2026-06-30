package com.opsi.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFE6E1E5),
    background = Color(0xFF000000),
    surface = Color(0xFF000000)
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1C1B1F),
    background = Color(0xFF000000),
    surface = Color(0xFF000000)
)

@Composable
fun OpsiTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}
