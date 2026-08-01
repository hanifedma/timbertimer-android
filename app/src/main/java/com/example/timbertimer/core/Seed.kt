package com.example.timbertimer.core

import com.example.timbertimer.data.model.TreeSpecies
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/**
 * The web app derives a tree's species, colours and posture from a hash of the
 * session name, so the same session always grows the same tree without storing
 * anything extra. This file reproduces that arithmetic bit for bit, which is
 * what keeps a forest looking identical on the phone and on the website.
 *
 * Deliberately free of any Compose or Android import: it is the one part of the
 * port whose exactness can be checked against the original in a plain JVM test.
 */
object Seed {

    /** The web app's `getTreeSeed`: names are matched case- and space-insensitively. */
    fun treeSeed(title: String?): String =
        title?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "deep focus"

    /**
     * `hash = (hash * 31 + charCode) >>> 0` over UTF-16 code units.
     *
     * Kept in a [Long] masked to 32 bits: JS does this in doubles and coerces
     * with `>>> 0`, and every intermediate here stays well inside 2^53, so
     * masking gives exactly the same value.
     */
    fun hash(value: String): Long {
        var hash = 0L
        for (char in value) {
            hash = (hash * 31 + char.code) and UINT32_MASK
        }
        return hash
    }

    /** `seededRange` — a stable pseudo-random in [min, max] for one seed and salt. */
    fun range(seed: String, salt: String, min: Float, max: Float): Float {
        val unit = (hash("$seed:$salt") % 1000L).toFloat() / 999f
        return min + (max - min) * unit
    }

    /**
     * The species a brand-new session name defaults to.
     *
     * The web app mixes the hash before taking it modulo seven, so names spread
     * across species instead of clustering. That mix runs through a JS `number`
     * multiply whose result exceeds 2^53 and therefore *loses precision* — and
     * the lost bits still decide the answer. Doubles are used here for the same
     * step, and ECMAScript's ToUint32 is spelled out below, so this lands on the
     * same species the website would show rather than merely a plausible one.
     */
    fun defaultSpeciesFor(title: String?): TreeSpecies {
        // `hash` is a uint32; reinterpreting it as Int gives JS's signed view.
        var mixed: Int = hash(treeSeed(title) + ":species").toInt()
        mixed = mixed xor (mixed ushr 13)

        val product: Double = mixed.toDouble() * 0x5BD1E995L.toDouble()
        var unsigned: Long = toUint32(product)
        unsigned = (unsigned xor (unsigned ushr 15)) and UINT32_MASK

        val species = TreeSpecies.choosable
        return species[(unsigned % species.size).toInt()]
    }

    /**
     * The leaf and bark colours for a session, as raw HSL.
     *
     * Kept as numbers rather than colour objects so the values can be compared
     * against the website's directly. CSS wraps hues past 360; that wrap is
     * applied here, since Compose requires them inside the circle.
     */
    fun paletteSpec(seedSource: String?): TreePaletteSpec {
        val seed = hash(treeSeed(seedSource))
        val hue = 80f + (seed % 94L)
        val leafSat = 48f + ((seed / 17L) % 18L)
        val leafLight = 58f + ((seed / 29L) % 13L)
        val barkHue = 22f + ((seed / 7L) % 32L)

        return TreePaletteSpec(
            leafA = Hsl(hue, leafSat, leafLight),
            leafB = Hsl(
                hue + 14f + (seed % 12L),
                maxOf(38f, leafSat - 9f),
                29f + ((seed / 41L) % 10L),
            ),
            barkA = Hsl(barkHue, 44f + (seed % 9L), 49f + ((seed / 11L) % 12L)),
            barkB = Hsl(barkHue, 38f + ((seed / 13L) % 9L), 27f + ((seed / 19L) % 8L)),
        )
    }

    /** ECMAScript ToUint32: truncate toward zero, then take it modulo 2^32. */
    private fun toUint32(value: Double): Long {
        if (!value.isFinite()) return 0L
        val truncated = value.sign * floor(abs(value))
        // 2^32 fits comfortably in Long, and |truncated| here stays under 2^62.
        val wrapped = truncated.toLong() % UINT32_SIZE
        return if (wrapped < 0L) wrapped + UINT32_SIZE else wrapped
    }

    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val UINT32_SIZE = 0x100000000L
}

/** One colour, in the same units CSS's `hsl()` takes: degrees and percentages. */
data class Hsl(val hue: Float, val saturationPercent: Float, val lightnessPercent: Float) {
    /** Degrees folded into 0..360, which is the range Compose accepts. */
    val wrappedHue: Float get() = ((hue % 360f) + 360f) % 360f

    val saturation: Float get() = (saturationPercent / 100f).coerceIn(0f, 1f)

    val lightness: Float get() = (lightnessPercent / 100f).coerceIn(0f, 1f)
}

/** The four colours every tree drawing is built from. */
data class TreePaletteSpec(
    val leafA: Hsl,
    val leafB: Hsl,
    val barkA: Hsl,
    val barkB: Hsl,
)

/**
 * How grown a tree looks, by how many minutes went into it. Four stages, so a
 * long session is visibly a bigger tree than a short one.
 */
fun focusGrowthStage(minutes: Int): Int {
    val safe = maxOf(0, minutes)
    return when {
        safe <= 15 -> 0
        safe <= 30 -> 1
        safe <= 45 -> 2
        else -> 3
    }
}

/** The scale a planted tree is drawn at in the forest: stage plus a time bonus. */
fun groveTreeScale(minutes: Int, seed: String): Float {
    val safe = maxOf(0, minutes)
    val capped = minOf(120, safe)
    val stageScales = floatArrayOf(0.4f, 0.51f, 0.62f, 0.74f)
    val timeBonus = (capped / 120f) * 0.09f
    return stageScales[focusGrowthStage(minutes)] + timeBonus +
        Seed.range(seed, "size", -0.004f, 0.004f)
}
