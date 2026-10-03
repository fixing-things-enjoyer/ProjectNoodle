package com.github.fixingthingsenjoyer.projectnoodle.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors =
    lightColorScheme(
        primary = NoodleViolet,
        onPrimary = Color.White,
        primaryContainer = NoodleLavender,
        onPrimaryContainer = Color(0xFF25105D),
        secondary = Color(0xFF52634F),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD5E8CE),
        onSecondaryContainer = Color(0xFF142011),
        tertiary = Color(0xFF79546D),
        tertiaryContainer = Color(0xFFFFD8EE),
        onTertiaryContainer = Color(0xFF301126),
        background = NoodlePaper,
        onBackground = NoodleInk,
        surface = NoodlePaper,
        onSurface = NoodleInk,
        surfaceContainerLow = Color(0xFFF5F0FA),
        surfaceContainer = Color(0xFFEFEAF4),
        surfaceContainerHigh = Color(0xFFEAE4EF),
        onSurfaceVariant = NoodleMuted,
        outline = Color(0xFF7B7485),
        outlineVariant = Color(0xFFCCC4D6),
    )
private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFCEBEFF),
        onPrimary = Color(0xFF36216F),
        primaryContainer = Color(0xFF4E3894),
        onPrimaryContainer = NoodleLavender,
        secondary = Color(0xFFB9CCB3),
        secondaryContainer = Color(0xFF3B4B38),
        onSecondaryContainer = Color(0xFFD5E8CE),
        tertiary = Color(0xFFEBB9D6),
        tertiaryContainer = Color(0xFF5F3D54),
        onTertiaryContainer = Color(0xFFFFD8EE),
        background = NoodleDark,
        onBackground = Color(0xFFE8E0EF),
        surface = NoodleDark,
        onSurface = Color(0xFFE8E0EF),
        surfaceContainerLow = Color(0xFF1D1925),
        surfaceContainer = Color(0xFF231F2B),
        surfaceContainerHigh = Color(0xFF2E2936),
        onSurfaceVariant = Color(0xFFCDC3D8),
        outline = Color(0xFF968DA2),
        outlineVariant = Color(0xFF494152),
    )

@Composable
fun ProjectNoodleTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= 31 ->
                if (darkTheme) dynamicDarkColorScheme(LocalContext.current)
                else dynamicLightColorScheme(LocalContext.current)
            darkTheme -> DarkColors
            else -> LightColors
        }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography,
        shapes =
            Shapes(
                small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(18.dp),
                large = RoundedCornerShape(24.dp),
            ),
        content = content,
    )
}
