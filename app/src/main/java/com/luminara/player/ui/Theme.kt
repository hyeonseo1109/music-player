package com.luminara.player.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.luminara.player.data.ThemeMode

private val DarkColors = darkColorScheme(
    primary = ElectricPurple, onPrimary = Color.White, primaryContainer = Color(0xFF341061), onPrimaryContainer = PurpleWhite,
    secondary = NeonViolet, onSecondary = Color(0xFF160021), secondaryContainer = Color(0xFF301040),
    background = Color(0xFF020106), surface = Color(0xE80A0611), surfaceVariant = Color(0xD9160D25),
    onBackground = PurpleWhite, onSurface = PurpleWhite, onSurfaceVariant = Color(0xFFBEB2CC),
    outline = Color(0xFF684889), outlineVariant = Color(0xFF342044),
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF6E43CC), onPrimary = Color.White, primaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFF326A7E), background = Color(0xFFFFF9FF), surface = Color.White,
    surfaceVariant = Color(0xFFF1EAFA), onBackground = Color(0xFF1C1720), onSurface = Color(0xFF1C1720),
)

@Composable fun LuminaraTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode == ThemeMode.DARK || (mode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = Typography(
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(letterSpacing = (-0.7).sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Light),
            headlineSmall = MaterialTheme.typography.headlineSmall.copy(letterSpacing = (-0.4).sp),
            titleLarge = MaterialTheme.typography.titleLarge.copy(letterSpacing = (-0.3).sp),
            labelLarge = MaterialTheme.typography.labelLarge.copy(letterSpacing = 0.4.sp),
        ),
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(34.dp),
        ),
        content = content,
    )
}
