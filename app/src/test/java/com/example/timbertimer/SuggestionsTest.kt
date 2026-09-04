package com.example.timbertimer

import com.example.timbertimer.core.Suggestions
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.TreeSpecies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the task field offers while it is being typed into.
 *
 * The web app hands this job to the browser and gets a `<datalist>`; Compose
 * has no equivalent, so the same behaviour is spelled out in [Suggestions] —
 * and the rules with actual judgement in them are pinned here rather than left
 * to be discovered by typing into a phone.
 */
class SuggestionsTest {

    private var clock = 1_700_000_000_000L

    /** Each call is later than the last, so "newest first" has a meaning. */
    private fun record(title: String, projectId: String = Projects.DEFAULT_ID): FocusRecord {
        clock += 60_000L
        return FocusRecord(
            id = title + clock,
            title = title,
            projectId = projectId,
            actualMinutes = 25,
            startedAt = clock,
            endedAt = clock + 25 * 60_000L,
            treeKind = TreeSpecies.PINE.label,
            createdAt = clock,
            updatedAt = clock,
        )
    }

    @Test
    fun `history is newest first`() {
        val history = Suggestions.history(listOf(record("oldest"), record("newest")))

        assertEquals(listOf("newest", "oldest"), history)
    }

    @Test
    fun `the same name twice appears once, spelled the way it was last written`() {
        val history = Suggestions.history(listOf(record("Deep Focus"), record("deep focus")))

        // One entry, and the recent spelling — that is the one being reused.
        assertEquals(listOf("deep focus"), history)
    }

    @Test
    fun `rests are not offered as task names`() {
        val history = Suggestions.history(
            listOf(record("Writing"), record("Rest", Projects.REST_ID))
        )

        assertEquals(listOf("Writing"), history)
    }

    @Test
    fun `blank and whitespace-only titles are left out`() {
        val history = Suggestions.history(listOf(record("Writing"), record("   ")))

        assertEquals(listOf("Writing"), history)
    }

    @Test
    fun `titles are offered trimmed`() {
        assertEquals(listOf("Writing"), Suggestions.history(listOf(record("  Writing  "))))
    }

    @Test
    fun `an empty query offers the recent names`() {
        val history = listOf("c", "b", "a")

        assertEquals(history, Suggestions.matching(history, ""))
        // Whitespace is not a query either.
        assertEquals(history, Suggestions.matching(history, "   "))
    }

    @Test
    fun `matching is case insensitive and finds the text anywhere`() {
        val history = listOf("Friday deploy", "Deep focus")

        assertEquals(listOf("Friday deploy"), Suggestions.matching(history, "DEPLOY"))
    }

    @Test
    fun `names that start with the text come first`() {
        // "de" is inside "Friday deploy" but starts "Deep focus", and the one
        // being reached for is almost always the one being spelled out.
        val history = listOf("Friday deploy", "Deep focus")

        assertEquals(listOf("Deep focus", "Friday deploy"), Suggestions.matching(history, "de"))
    }

    @Test
    fun `the name already typed is not offered back`() {
        val history = listOf("Deep focus", "Deep focus review")

        // Exactly what is in the field is not a suggestion, whatever its case.
        assertEquals(listOf("Deep focus review"), Suggestions.matching(history, "deep focus"))
    }

    @Test
    fun `a query nothing matches offers nothing`() {
        assertTrue(Suggestions.matching(listOf("Writing"), "zzz").isEmpty())
    }

    @Test
    fun `the menu is capped, and the cap keeps the closest matches`() {
        // Twenty that merely contain the text, then one that starts with it.
        val history = List(20) { "task about x$it" } + "xylophone"

        val matches = Suggestions.matching(history, "x")

        assertEquals(Suggestions.LIMIT, matches.size)
        // The prefix match survives the cap; that is the point of ranking
        // before truncating rather than after.
        assertTrue("prefix match was cut", "xylophone" in matches)
    }

    @Test
    fun `a full page of prefix matches stops early and still fills the menu`() {
        val history = List(50) { "xtask $it" }

        val matches = Suggestions.matching(history, "x")

        assertEquals(Suggestions.LIMIT, matches.size)
        assertEquals("xtask 0", matches.first())
    }

    @Test
    fun `no history means nothing to offer`() {
        assertTrue(Suggestions.history(emptyList()).isEmpty())
        assertTrue(Suggestions.matching(emptyList(), "anything").isEmpty())
    }
}
