package com.example.timbertimer

import com.example.timbertimer.core.Palette
import com.example.timbertimer.core.Seed
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.TreeSpecies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parity with the web client's colour and species arithmetic.
 *
 * Every expectation below was produced by running the real functions out of the
 * website's `src/app.js` under Node, so this is a comparison against the
 * original rather than against a second reading of it. If any of these drift, a
 * project that is purple on the website would be some other colour on the phone
 * — the kind of difference a user notices immediately and cannot explain.
 *
 * The species and colour cases are the delicate ones: the web app's bit-mixing
 * step overflows 2^53 in JavaScript and loses precision, and the lost bits still
 * decide the answer. These pin that behaviour down.
 */
class SeedParityTest {

    private data class Case(
        val name: String,
        val seed: String,
        val hash: Long,
        val color: String,
        val species: String,
        val tilt: Float,
    )

    private val cases = listOf(
        Case("Deep focus", "deep focus", 1219298468L, "#0b83d9", "canopy", 3.3583584f),
        Case("deep focus", "deep focus", 1219298468L, "#0b83d9", "canopy", 3.3583584f),
        Case("eating ayam", "eating ayam", 2232258362L, "#e57cd8", "mangrove", -4.4694695f),
        Case("Rest", "rest", 3496916L, "#566614", "pine", -3.048048f),
        Case("study", "study", 109776329L, "#465bb3", "canopy", 4.8298298f),
        Case("코딩", "코딩", 1691733L, "#c9806b", "bamboo", -1.6966967f),
        Case("Reading", "reading", 1080413836L, "#465bb3", "pine", -1.7667668f),
        Case("Errands", "errands", 2815561825L, "#e57cd8", "fern", -2.2972973f),
        Case("project x", "project x", 3400133937L, "#e36a00", "canopy", -2.8578579f),
        Case("gym", "gym", 102843L, "#c9806b", "kapok", 3.7687688f),
        Case("", "deep focus", 1219298468L, "#0b83d9", "canopy", 3.3583584f),
        Case("  Trimmed  ", "trimmed", 3235260010L, "#06a893", "palm", 1.0560561f),
    )

    @Test
    fun `seed normalisation matches the web client`() {
        cases.forEach { case ->
            assertEquals("seed for '${case.name}'", case.seed, Seed.treeSeed(case.name))
        }
    }

    @Test
    fun `hash matches the web client`() {
        cases.forEach { case ->
            assertEquals("hash for '${case.seed}'", case.hash, Seed.hash(case.seed))
        }
    }

    @Test
    fun `a project name picks the web client's species`() {
        cases.forEach { case ->
            assertEquals(
                "species for '${case.name}'",
                case.species,
                Seed.defaultSpeciesFor(case.name).id,
            )
        }
    }

    @Test
    fun `a project name picks the web client's colour`() {
        cases.forEach { case ->
            assertEquals(
                "colour for '${case.name}'",
                case.color,
                Projects.colorForName(case.name),
            )
        }
    }

    @Test
    fun `seeded range matches the web client`() {
        cases.forEach { case ->
            assertEquals(
                "tilt for '${case.name}'",
                case.tilt,
                Seed.range(case.seed, "tilt", -5f, 5f),
                0.0005f,
            )
        }
    }

    @Test
    fun `the default species is never the wilted one`() {
        // Wilting is an outcome, not a choice, so it must stay out of the pool.
        (0..500).forEach { index ->
            assertTrue(Seed.defaultSpeciesFor("project $index") != TreeSpecies.WILTED)
        }
    }

    // ---------- colour ----------

    private data class PaletteCase(
        val color: String,
        val leafA: Triple<Float, Float, Float>,
        val leafB: Triple<Float, Float, Float>,
        val barkA: Triple<Float, Float, Float>,
        val barkB: Triple<Float, Float, Float>,
        val mutedLeafA: Triple<Float, Float, Float>,
    )

    private val paletteCases = listOf(
        PaletteCase(
            "#9e5bd9",
            Triple(272f, 62f, 66f), Triple(280f, 57f, 40f),
            Triple(82f, 34f, 46f), Triple(82f, 30f, 30f),
            Triple(272f, 21f, 70f),
        ),
        PaletteCase(
            "#0b83d9",
            Triple(205f, 90f, 51f), Triple(213f, 83f, 25f),
            Triple(67f, 34f, 46f), Triple(67f, 30f, 30f),
            Triple(205f, 31f, 57f),
        ),
        PaletteCase(
            "#2da608",
            Triple(106f, 91f, 40f), Triple(114f, 84f, 14f),
            Triple(45f, 34f, 46f), Triple(45f, 30f, 30f),
            Triple(106f, 31f, 46f),
        ),
        PaletteCase(
            "#a1866f",
            Triple(28f, 21f, 59f), Triple(36f, 19f, 33f),
            Triple(28f, 34f, 46f), Triple(28f, 30f, 30f),
            Triple(28f, 7f, 65f),
        ),
        PaletteCase(
            "#8e8e93",
            Triple(240f, 2f, 63f), Triple(248f, 2f, 37f),
            Triple(75f, 34f, 46f), Triple(75f, 30f, 30f),
            Triple(240f, 1f, 69f),
        ),
        PaletteCase(
            "#c7af14",
            Triple(52f, 82f, 49f), Triple(60f, 75f, 23f),
            Triple(33f, 34f, 46f), Triple(33f, 30f, 30f),
            Triple(52f, 28f, 55f),
        ),
    )

    @Test
    fun `a project colour paints the web client's tree`() {
        paletteCases.forEach { case ->
            val palette = Palette.treePalette(case.color)
            assertHsl("leafA of ${case.color}", case.leafA, palette.leafA)
            assertHsl("leafB of ${case.color}", case.leafB, palette.leafB)
            assertHsl("barkA of ${case.color}", case.barkA, palette.barkA)
            assertHsl("barkB of ${case.color}", case.barkB, palette.barkB)

            val muted = Palette.treePalette(case.color, muted = true)
            assertHsl("muted leafA of ${case.color}", case.mutedLeafA, muted.leafA)
        }
    }

    @Test
    fun `readable ink matches the web client, per theme`() {
        val inkCases = listOf(
            Triple("#9e5bd9", Triple(272f, 62f, 66f), Triple(272f, 60f, 42f)),
            Triple("#0b83d9", Triple(205f, 90f, 66f), Triple(205f, 87f, 42f)),
            Triple("#2da608", Triple(106f, 91f, 66f), Triple(106f, 87f, 34f)),
            Triple("#a1866f", Triple(28f, 45f, 66f), Triple(28f, 40f, 42f)),
        )
        inkCases.forEach { (color, dark, light) ->
            assertHsl("dark ink of $color", dark, Palette.ink(color, dark = true))
            assertHsl("light ink of $color", light, Palette.ink(color, dark = false))
        }
    }

    @Test
    fun `only a six-digit hex counts as a colour`() {
        assertEquals("#9e5bd9", Palette.normalizeColor("#9E5BD9"))
        assertEquals("#9e5bd9", Palette.normalizeColor("  #9e5bd9 "))
        assertNull(Palette.normalizeColor("9e5bd9"))
        assertNull(Palette.normalizeColor("#abc"))
        assertNull(Palette.normalizeColor(""))
        assertNull(Palette.normalizeColor(null))
    }

    @Test
    fun `an unrecognised colour still paints something rather than crashing`() {
        val palette = Palette.treePalette("not a colour")
        // The neutral grey the web app falls back to, so a record whose project
        // was deleted elsewhere still renders.
        assertHsl("fallback leafA", Triple(240f, 2f, 63f), palette.leafA)
    }

    @Test
    fun `every palette colour is distinct, so two projects cannot look alike`() {
        assertEquals(Projects.COLORS.size, Projects.COLORS.toSet().size)
        assertEquals(16, Projects.COLORS.size)
        Projects.COLORS.forEach { color ->
            assertEquals(color, Palette.normalizeColor(color))
        }
    }

    @Test
    fun `a free colour walks on from the name's own until one is unused`() {
        val taken = Projects.COLORS.take(4).mapIndexed { index, color ->
            com.example.timbertimer.data.model.Project(
                id = "p$index",
                name = "p$index",
                color = color,
                tree = "pine",
                sortOrder = index,
                createdAt = 0L,
                updatedAt = 0L,
            )
        }
        val chosen = Projects.freeColorForName("Deep focus", taken, skipId = null)
        assertTrue("expected an unused colour, got $chosen", taken.none { it.color == chosen })
        // Nothing taken means the name's own colour is free, and is what it gets.
        assertEquals(
            Projects.colorForName("Deep focus"),
            Projects.freeColorForName("Deep focus", emptyList(), null),
        )
    }

    private fun assertHsl(
        message: String,
        expected: Triple<Float, Float, Float>,
        actual: com.example.timbertimer.core.Hsl,
    ) {
        assertEquals("$message hue", expected.first, actual.hue, 0.001f)
        assertEquals("$message saturation", expected.second, actual.saturationPercent, 0.001f)
        assertEquals("$message lightness", expected.third, actual.lightnessPercent, 0.001f)
    }
}
