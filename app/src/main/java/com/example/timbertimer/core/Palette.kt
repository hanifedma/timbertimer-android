package com.example.timbertimer.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Colour arithmetic, ported from the web client.
 *
 * Since projects arrived, a tree is painted from *its project's* colour rather
 * than from a hash of the session name: a red project grows red trees, and
 * recolouring a project re-plants its whole forest. Bark stays woody, nudged a
 * little toward the project's hue.
 *
 * The web app builds CSS `hsl()` strings, which round each component to a whole
 * number. That rounding is reproduced here, so a colour is bit-for-bit the one
 * the website would paint rather than merely a close neighbour.
 *
 * Deliberately free of any Compose or Android import, so the arithmetic can be
 * checked against the original in a plain JVM test.
 */
object Palette {

    /** `#rrggbb`, lowercased — or null when the value is not a six-digit hex. */
    fun normalizeColor(value: String?): String? {
        val hex = value?.trim().orEmpty()
        if (!HEX.matches(hex)) return null
        return hex.lowercase()
    }

    fun rgb(hex: String): Triple<Int, Int, Int> {
        val value = normalizeColor(hex) ?: FALLBACK
        return Triple(
            value.substring(1, 3).toInt(16),
            value.substring(3, 5).toInt(16),
            value.substring(5, 7).toInt(16),
        )
    }

    fun hsl(hex: String): Hsl {
        val (r, g, b) = rgb(hex)
        val rr = r / 255f
        val gg = g / 255f
        val bb = b / 255f
        val maxC = max(rr, max(gg, bb))
        val minC = min(rr, min(gg, bb))
        val lightness = (maxC + minC) / 2f
        val delta = maxC - minC

        if (delta == 0f) return Hsl(0f, 0f, lightness * 100f)

        val saturation = delta / (1f - abs(2f * lightness - 1f))
        var hue = when (maxC) {
            rr -> ((gg - bb) / delta) % 6f
            gg -> (bb - rr) / delta + 2f
            else -> (rr - gg) / delta + 4f
        }
        hue = (hue * 60f).roundToInt().toFloat()
        if (hue < 0f) hue += 360f
        return Hsl(hue, saturation * 100f, lightness * 100f)
    }

    /** The four colours a tree is drawn with, derived from its project's colour. */
    fun treePalette(color: String): TreePaletteSpec {
        val base = hsl(color)
        val h = base.hue
        val s = base.saturationPercent
        val l = base.lightnessPercent
        // Pull the bark toward brown while keeping a hint of the project's hue.
        val barkHue = h * 0.22f + 28f * 0.78f
        return TreePaletteSpec(
            leafA = css(h, s, (l + 6f).coerceIn(26f, 74f)),
            leafB = css(h + 8f, s * 0.92f, (l - 20f).coerceIn(14f, 52f)),
            barkA = css(barkHue, 34f, 46f),
            barkB = css(barkHue, 30f, 30f),
        )
    }

    /**
     * A readable version of a project's colour for text on the current theme:
     * lightened on dark, darkened on light.
     */
    fun ink(color: String, dark: Boolean): Hsl {
        val base = hsl(color)
        return if (dark) {
            css(base.hue, max(45f, base.saturationPercent), max(base.lightnessPercent, 66f).coerceIn(66f, 84f))
        } else {
            css(base.hue, max(40f, base.saturationPercent * 0.96f), min(base.lightnessPercent, 42f).coerceIn(24f, 42f))
        }
    }

    /** The translucent wash a project's chips and calendar blocks sit on. */
    fun softAlpha(dark: Boolean): Float = if (dark) 0.2f else 0.14f

    /** CSS `hsl()` rounds every component; matching that keeps colours identical. */
    private fun css(hue: Float, saturation: Float, lightness: Float): Hsl = Hsl(
        hue = (((hue % 360f) + 360f) % 360f).roundToInt().toFloat(),
        saturationPercent = saturation.roundToInt().toFloat().coerceIn(0f, 100f),
        lightnessPercent = lightness.roundToInt().toFloat().coerceIn(0f, 100f),
    )

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")
    private const val FALLBACK = "#8e8e93"
}
