package com.luminara.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.luminara.player.data.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB69CFF), onPrimary = Color(0xFF25104F), primaryContainer = Color(0xFF3C246B),
    secondary = Color(0xFF9BDCFB), background = Color(0xFF08060D), surface = Color(0xFF110D1A),
    surfaceVariant = Color(0xFF1B1428), onBackground = Color(0xFFF4EEFF), onSurface = Color(0xFFF4EEFF),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF6E43CC), onPrimary = Color.White, primaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF326A7E), background = Color(0xFFFFF9FF), surface = Color.White,
    surfaceVariant = Color(0xFFF1EAFA), onBackground = Color(0xFF1C1720), onSurface = Color(0xFF1C1720),
)

@Composable fun LuminaraTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Typography(), content = content)
}
