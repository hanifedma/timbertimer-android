package com.example.timbertimer.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.example.timbertimer.core.Hsl
import com.example.timbertimer.core.Palette
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.ui.theme.LocalIsDarkTheme

/** The four colours a tree is drawn with, as Compose colours. */
data class TreePalette(
    val leafA: Color,
    val leafB: Color,
    val barkA: Color,
    val barkB: Color,
)

/**
 * A project's colour in the three roles the UI needs it in: the colour itself
 * for dots and bars, a readable version for text on the current theme, and a
 * translucent wash for the surfaces it labels.
 */
data class ProjectColors(
    val base: Color,
    val ink: Color,
    val soft: Color,
)

/** The palette a project's trees are painted from. */
fun treePaletteOf(color: String): TreePalette {
    val spec = Palette.treePalette(color)
    return TreePalette(
        leafA = spec.leafA.toColor(),
        leafB = spec.leafB.toColor(),
        barkA = spec.barkA.toColor(),
        barkB = spec.barkB.toColor(),
    )
}

@Composable
fun rememberTreePalette(color: String): TreePalette =
    remember(color) { treePaletteOf(color) }

@Composable
fun rememberTreePalette(project: Project): TreePalette = rememberTreePalette(project.color)

@Composable
fun projectColors(color: String): ProjectColors {
    val dark = LocalIsDarkTheme.current
    return remember(color, dark) {
        val base = solidColor(color)
        ProjectColors(
            base = base,
            ink = Palette.ink(color, dark).toColor(),
            soft = base.copy(alpha = Palette.softAlpha(dark)),
        )
    }
}

@Composable
fun projectColors(project: Project): ProjectColors = projectColors(project.color)

/** The project's own hex, straight through — no theme adjustment. */
fun solidColor(color: String): Color {
    val (r, g, b) = Palette.rgb(color)
    return Color(red = r, green = g, blue = b)
}

private fun Hsl.toColor(): Color =
    Color.hsl(hue = wrappedHue, saturation = saturation, lightness = lightness)
