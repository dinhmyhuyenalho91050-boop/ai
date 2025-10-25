package com.example.htmlapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkPalette = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color.White,
    secondary = Color(0xFFFBBF24),
    onSecondary = Color(0xFF151921),
    tertiary = Color(0xFF10B981),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6E8EB),
    surface = Color(0xFF151921),
    onSurface = Color(0xFFE6E8EB),
    surfaceVariant = Color(0xFF1F2937),
    outline = Color(0xFF1F2937),
)

private val LightPalette = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    secondary = Color(0xFFF59E0B),
    onSecondary = Color(0xFF0F172A),
    tertiary = Color(0xFF10B981),
    background = Color(0xFFF3F4F6),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE5E7EB),
    outline = Color(0xFFCBD5F5),
)

@Composable
fun HtmlAppTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme: ColorScheme = if (darkTheme) DarkPalette else LightPalette

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content,
    )
}
