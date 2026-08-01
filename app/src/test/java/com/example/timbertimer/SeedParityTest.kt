package com.example.timbertimer

import com.example.timbertimer.core.Seed
import com.example.timbertimer.data.model.TreeSpecies
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Parity with the web client's tree arithmetic.
 *
 * Every expectation below was produced by running the real functions out of the
 * website's `src/app.js` under Node, so this is a comparison against the
 * original rather than against a second reading of it. If any of these drift, a
 * session that grows a pine on the website would grow something else on the
 * phone — the kind of difference a user notices immediately and cannot explain.
 *
 * The species case is the delicate one: the web app's bit-mixing step overflows
 * 2^53 in JavaScript and loses precision, and the lost bits still decide the
 * answer. These cases pin that behaviour down.
 */
class SeedParityTest {

    private data class Case(
        val name: String,
        val seed: String,
        val hash: Long,
        val species: String,
        val hue: Float,
        val leafSat: Float,
        val leafLight: Float,
        val barkHue: Float,
        val tilt: Float,
    )

    private val cases = listOf(
        Case("Deep focus", "deep focus", 1219298468L, "canopy", 108f, 57f, 63f, 45f, 3.3583584f),
        Case("deep focus", "deep focus", 1219298468L, "canopy", 108f, 57f, 63f, 45f, 3.3583584f),
        Case("eating ayam", "eating ayam", 2232258362L, "mangrove", 116f, 65f, 67f, 25f, -4.4694695f),
        Case("Rest", "rest", 3496916L, "pine", 102f, 62f, 66f, 29f, -3.048048f),
        Case("study", "study", 109776329L, "canopy", 107f, 51f, 69f, 50f, 4.8298298f),
        Case("코딩", "코딩", 1691733L, "bamboo", 95f, 57f, 62f, 34f, -1.6966967f),
        Case("a", "a", 97L, "kapok", 83f, 53f, 61f, 35f, 4.909910f),
        Case("zzz", "zzz", 121146L, "kapok", 154f, 64f, 62f, 48f, 0.1751752f),
        Case("Write report", "write report", 2742525077L, "canopy", 145f, 52f, 62f, 38f, -3.5385385f),
        Case("  Trimmed  ", "trimmed", 3235260010L, "palm", 144f, 50f, 65f, 23f, 1.0560561f),
        Case("", "deep focus", 1219298468L, "canopy", 108f, 57f, 63f, 45f, 3.3583584f),
        Case("project x", "project x", 3400133937L, "canopy", 139f, 62f, 69f, 33f, -2.8578579f),
        Case("gym", "gym", 102843L, "kapok", 87f, 49f, 68f, 25f, 3.7687688f),
        Case("reading", "reading", 1080413836L, "pine", 100f, 51f, 60f, 23f, -1.7667668f),
        Case("밥 먹기", "밥 먹기", 1436436722L, "canopy", 148f, 59f, 70f, 52f, 1.3763764f),
        Case("Timber", "timber", 3421295863L, "canopy", 127f, 55f, 62f, 29f, -2.1971972f),
        Case("focus 25", "focus 25", 52484747L, "canopy", 115f, 62f, 68f, 51f, 3.3683684f),
        Case("hello world", "hello world", 1794106052L, "canopy", 136f, 60f, 70f, 22f, -4.0090090f),
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
    fun `default species matches the web client`() {
        cases.forEach { case ->
            assertEquals(
                "species for '${case.name}'",
                case.species,
                Seed.defaultSpeciesFor(case.name).id,
            )
        }
    }

    @Test
    fun `palette matches the web client`() {
        cases.forEach { case ->
            val spec = Seed.paletteSpec(case.name)
            assertEquals("hue for '${case.name}'", case.hue, spec.leafA.hue, 0.001f)
            assertEquals("sat for '${case.name}'", case.leafSat, spec.leafA.saturationPercent, 0.001f)
            assertEquals("light for '${case.name}'", case.leafLight, spec.leafA.lightnessPercent, 0.001f)
            assertEquals("bark for '${case.name}'", case.barkHue, spec.barkA.hue, 0.001f)
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
    fun `hues past the circle wrap the way CSS does`() {
        // leafB adds up to 26 degrees to the leaf hue, which can exceed 360.
        val spec = Seed.paletteSpec("zzz")
        assert(spec.leafB.wrappedHue in 0f..360f) { "hue ${spec.leafB.wrappedHue} out of range" }
    }

    @Test
    fun `the default species is never the wilted one`() {
        // Wilting is an outcome, not a choice, so it must stay out of the pool.
        (0..500).forEach { index ->
            assert(Seed.defaultSpeciesFor("session $index") != TreeSpecies.WILTED)
        }
    }
}
