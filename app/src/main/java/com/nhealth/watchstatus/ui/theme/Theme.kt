package com.nhealth.watchstatus.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkScheme = darkColorScheme(
    primary = ElectricBlue,
    secondary = CyanAccent,
    tertiary = RoseAccent,
    background = MidnightNavy,
    surface = InkBlue,
    onPrimary = CloudWhite,
    onBackground = CloudWhite,
    onSurface = CloudWhite
)

private val LightScheme = lightColorScheme(
    primary = ElectricBlue,
    secondary = CyanAccent,
    tertiary = RoseAccent,
    background = CloudWhite,
    surface = ColorTokens.LightSurface,
    onPrimary = CloudWhite,
    onBackground = ColorTokens.LightText,
    onSurface = ColorTokens.LightText
)

private object ColorTokens {
    val LightSurface = androidx.compose.ui.graphics.Color(0xFFEFF4FF)
    val LightText = androidx.compose.ui.graphics.Color(0xFF10213D)
}

@Composable
fun NHealthTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkScheme
        else -> LightScheme
    }

    if (context is Activity) {
        // Intentionally left minimal; activity-level edge-to-edge can be layered here.
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
