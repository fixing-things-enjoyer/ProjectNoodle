package com.example.projectnoodle.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = TokyoNightPrimary,
    onPrimary = TokyoNightOnPrimary,
    primaryContainer = TokyoNightPrimaryContainer,
    onPrimaryContainer = TokyoNightOnPrimaryContainer,
    secondary = TokyoNightSecondary,
    onSecondary = TokyoNightOnSecondary,
    secondaryContainer = TokyoNightSecondaryContainer,
    onSecondaryContainer = TokyoNightOnSecondaryContainer,
    tertiary = TokyoNightTertiary,
    onTertiary = TokyoNightOnTertiary,
    tertiaryContainer = TokyoNightTertiaryContainer,
    onTertiaryContainer = TokyoNightOnTertiaryContainer,
    background = TokyoNightBackground,
    onBackground = TokyoNightOnBackground,
    surface = TokyoNightSurface,
    onSurface = TokyoNightOnSurface,
    surfaceVariant = TokyoNightSurfaceVariant,
    onSurfaceVariant = TokyoNightOnSurfaceVariant,
    error = TokyoNightError,
    onError = TokyoNightOnError
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
    // You can define a "light Tokyo Night" palette here if you plan to support light theme
)

@Composable
fun ProjectNoodleTheme(
    darkTheme: Boolean = true, // Your app defaults to dark theme
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Setting status bar color to match the background color for a cohesive Tokyo Night look
            window.statusBarColor = colorScheme.background.toArgb()
            // Adjusting status bar icons color based on whether the theme is light or dark
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
