package com.remind.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class AppTheme { WHITE, DARK, BLACK }

private val WhiteScheme = lightColorScheme(
    primary = Color(0xFF2563EB),
    background = Color(0xFFFAFAF9),
    surface = Color.White,
    onBackground = Color(0xFF17171A),
    onSurface = Color(0xFF17171A),
    onSurfaceVariant = Color(0xFF9A9AA0),
    outline = Color(0xFFF1F0EE)
)
private val DarkScheme = darkColorScheme(
    primary = Color(0xFF6EA3FF),
    background = Color(0xFF121214),
    surface = Color(0xFF1C1C1E),
    onBackground = Color(0xFFECECF0),
    onSurface = Color(0xFFECECF0),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF2C2C2E)
)
private val BlackScheme = darkColorScheme(
    primary = Color(0xFF6EA3FF),
    background = Color.Black,
    surface = Color.Black,
    onBackground = Color(0xFFECECF0),
    onSurface = Color(0xFFECECF0),
    onSurfaceVariant = Color(0xFF8E8E93),
    outline = Color(0xFF1C1C1E)
)

@Composable
fun RemindTheme(theme: AppTheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = when (theme) {
            AppTheme.WHITE -> WhiteScheme
            AppTheme.DARK -> DarkScheme
            AppTheme.BLACK -> BlackScheme
        },
        content = content
    )
}
