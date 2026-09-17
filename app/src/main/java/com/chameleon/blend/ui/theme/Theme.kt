package com.chameleon.blend.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = JadeLightPrimary,
    onPrimary = JadeLightOnPrimary,
    primaryContainer = JadeLightPrimaryContainer,
    onPrimaryContainer = JadeLightOnPrimaryContainer,
    secondary = SandLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = SandLightSecondaryContainer,
    onSecondaryContainer = Color(0xFF281900),
    tertiary = CoralLightTertiary,
    onTertiary = Color.White,
    tertiaryContainer = CoralLightTertiaryContainer,
    onTertiaryContainer = Color(0xFF3A0A00),
    background = SurfaceLight,
    onBackground = OnSurfaceLight,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = Color(0xFF3F4946),
    surfaceContainer = SurfaceContainerLight,
    surfaceContainerHigh = Color(0xFFE9EDEA),
    surfaceContainerLow = Color(0xFFF6F8F6),
    outline = OutlineLight,
    outlineVariant = Color(0xFFBFC9C4),
)

private val DarkColors = darkColorScheme(
    primary = JadeDarkPrimary,
    onPrimary = JadeDarkOnPrimary,
    primaryContainer = JadeDarkPrimaryContainer,
    onPrimaryContainer = JadeDarkOnPrimaryContainer,
    secondary = SandDarkSecondary,
    onSecondary = Color(0xFF3F2D00),
    secondaryContainer = SandDarkSecondaryContainer,
    onSecondaryContainer = Color(0xFFFFDEA6),
    tertiary = CoralDarkTertiary,
    onTertiary = Color(0xFF561F14),
    tertiaryContainer = CoralDarkTertiaryContainer,
    onTertiaryContainer = Color(0xFFFFDAD1),
    background = SurfaceDark,
    onBackground = OnSurfaceDark,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = Color(0xFFBFC9C5),
    surfaceContainer = SurfaceContainerDark,
    surfaceContainerHigh = Color(0xFF242B29),
    surfaceContainerLow = Color(0xFF161C1A),
    outline = OutlineDark,
    outlineVariant = Color(0xFF3F4946),
)

@Composable
fun ChameleonTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ChameleonTypography,
        content = content,
    )
}
