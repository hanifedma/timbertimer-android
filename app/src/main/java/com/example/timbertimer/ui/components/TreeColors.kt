package com.example.timbertimer.ui.components

import androidx.compose.ui.graphics.Color
import com.example.timbertimer.core.Hsl
import com.example.timbertimer.core.Seed

/** The four colours a tree is drawn with, as Compose colours. */
data class TreePalette(
    val leafA: Color,
    val leafB: Color,
    val barkA: Color,
    val barkB: Color,
)

/**
 * Turns the seeded HSL spec into drawable colours. Split from [Seed] so the
 * colour arithmetic itself stays testable without a Compose runtime.
 */
fun treePalette(seedSource: String?): TreePalette {
    val spec = Seed.paletteSpec(seedSource)
    return TreePalette(
        leafA = spec.leafA.toColor(),
        leafB = spec.leafB.toColor(),
        barkA = spec.barkA.toColor(),
        barkB = spec.barkB.toColor(),
    )
}

private fun Hsl.toColor(): Color =
    Color.hsl(hue = wrappedHue, saturation = saturation, lightness = lightness)
