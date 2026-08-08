package com.example.timbertimer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val DarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkAccentInk,
    primaryContainer = DarkAccentSoft,
    onPrimaryContainer = DarkAccent,
    secondary = DarkAccent,
    onSecondary = DarkAccentInk,
    secondaryContainer = DarkSurface3,
    onSecondaryContainer = DarkInk,
    tertiary = DarkAmber,
    onTertiary = Color.Black,
    background = DarkBg,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = DarkMuted,
    // Cards, menus and navigation bars read these. Left at their defaults they
    // fall back to Material's baseline purple tint, which clashes badly with the
    // website's neutral greys.
    surfaceContainerLowest = DarkBg,
    surfaceContainerLow = Color(0xFF141416),
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurface2,
    surfaceContainerHighest = DarkSurface3,
    inverseSurface = Color(0xFFE4E4E8),
    inverseOnSurface = Color(0xFF17171D),
    outline = DarkLine,
    outlineVariant = DarkSurface2,
    error = DarkDanger,
    onError = Color.Black,
    errorContainer = DarkDangerSoft,
    onErrorContainer = DarkDanger,
)

private val LightColors = lightColorScheme(
    primary = LightAccent,
    onPrimary = LightAccentInk,
    primaryContainer = LightAccentSoft,
    onPrimaryContainer = LightAccent,
    secondary = LightAccent,
    onSecondary = LightAccentInk,
    secondaryContainer = LightSurface3,
    onSecondaryContainer = LightInk,
    tertiary = LightAmber,
    onTertiary = Color.White,
    background = LightBg,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurface2,
    onSurfaceVariant = LightMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = LightSurface2,
    surfaceContainerHigh = LightSurface3,
    surfaceContainerHighest = Color(0xFFE0E0E6),
    inverseSurface = Color(0xFF2E2E33),
    inverseOnSurface = Color(0xFFF5F5F7),
    outline = LightLine,
    outlineVariant = LightSurface3,
    error = LightDanger,
    onError = Color.White,
    errorContainer = LightDangerSoft,
    onErrorContainer = LightDanger,
)

/**
 * Material's defaults, with the two places this app actually needs its own
 * voice: the clock, and the small uppercase kickers above each panel.
 */
private val TimberTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.copy(
            fontWeight = FontWeight.Light,
            // Tabular figures would be ideal, but the platform sans already has
            // even digit widths; what matters is that the clock does not reflow
            // as the numbers change.
            fontFeatureSettings = "tnum",
        ),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
    )
}

/** The clock face. Sized by the caller, because it has to fit its container. */
fun clockStyle(fontSize: androidx.compose.ui.unit.TextUnit) = TextStyle(
    fontSize = fontSize,
    fontWeight = FontWeight.Light,
    fontFamily = FontFamily.Default,
    fontFeatureSettings = "tnum",
)

/**
 * Dynamic colour is deliberately not offered: Material You would repaint the app
 * from the wallpaper and break the visual link with the website, which is the
 * opposite of what a port is for.
 */
@Composable
fun TimberTimerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = TimberTypography,
            content = content,
        )
    }
}

/**
 * Whether the app is currently drawing its dark appearance.
 *
 * Project colours are adjusted for contrast per theme, and the only honest
 * source for "which theme" is the one the shell chose — the device's own dark
 * mode says nothing when the user has overridden it inside the app.
 */
val LocalIsDarkTheme = staticCompositionLocalOf { true }
