package com.example.timbertimer

import com.example.timbertimer.core.Time
import com.example.timbertimer.core.todayTotals
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.TreeSpecies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the time-by-project widget shows, and what the standing rest count
 * counts.
 *
 * Both read one snapshot, so the awkward parts — which day a record belongs to,
 * and the fact that Rest is simultaneously a project and a thing to be counted —
 * are worth pinning down here rather than by watching a home screen.
 */
class DayTotalsTest {

    private val today = Time.startOfDay(System.currentTimeMillis())
    private val todayKey = Time.localDateKey(today)

    private val book = ProjectBook(
        listOf(
            project(Projects.DEFAULT_ID, Projects.DEFAULT_NAME, "#0b83d9"),
            project(Projects.REST_ID, Projects.REST_NAME, Projects.REST_COLOR),
            project("writing", "Writing", "#d94182"),
        )
    )

    private fun project(id: String, name: String, color: String) = Project(
        id = id,
        name = name,
        color = color,
        tree = TreeSpecies.PINE.label,
        sortOrder = 0,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun at(dayStart: Long, hour: Int, minute: Int = 0): Long =
        dayStart + (hour * 60 + minute) * 60_000L

    private fun record(
        id: String,
        start: Long,
        minutes: Int,
        projectId: String = Projects.DEFAULT_ID,
    ) = FocusRecord(
        id = id,
        title = id,
        projectId = projectId,
        actualMinutes = minutes,
        startedAt = start,
        endedAt = start + minutes * 60_000L,
        treeKind = TreeSpecies.PINE.label,
        createdAt = start,
        updatedAt = start,
    )

    @Test
    fun `minutes are summed per project, longest first`() {
        val totals = todayTotals(
            records = listOf(
                record("a", at(today, 9), 25),
                record("b", at(today, 11), 20, "writing"),
                record("c", at(today, 14), 30),
                record("d", at(today, 16), 40, "writing"),
            ),
            projects = book,
            today = todayKey,
        )

        assertEquals(listOf("writing", Projects.DEFAULT_ID), totals.projects.map { it.id })
        assertEquals(60, totals.projects[0].minutes)
        assertEquals(55, totals.projects[1].minutes)
        assertEquals(115, totals.minutes)
        assertEquals(todayKey, totals.dateKey)
    }

    @Test
    fun `a project carries its own colour and stored name`() {
        val totals = todayTotals(
            records = listOf(record("a", at(today, 9), 10, "writing")),
            projects = book,
            today = todayKey,
        )

        val row = totals.projects.single()
        assertEquals("Writing", row.name)
        assertEquals("#d94182", row.color)
    }

    @Test
    fun `rests are both a project row and their own count`() {
        val totals = todayTotals(
            records = listOf(
                record("focus", at(today, 9), 50),
                record("r1", at(today, 10), 5, Projects.REST_ID),
                record("r2", at(today, 12), 10, Projects.REST_ID),
                record("r3", at(today, 15), 5, Projects.REST_ID),
            ),
            projects = book,
            today = todayKey,
        )

        // Three rests, but only one row, holding their twenty minutes together.
        assertEquals(3, totals.rests)
        val rest = totals.projects.single { it.id == Projects.REST_ID }
        assertEquals(20, rest.minutes)
    }

    @Test
    fun `yesterday is left out, and a record is counted on the day it ended`() {
        val totals = todayTotals(
            records = listOf(
                record("yesterday", at(Time.addDays(today, -1), 9), 60),
                // 23:30 last night to 00:30 this morning: the user finished it
                // today, so it counts today.
                record("overnight", at(Time.addDays(today, -1), 23, 30), 60),
                record("tomorrow", at(Time.addDays(today, 1), 9), 60),
            ),
            projects = book,
            today = todayKey,
        )

        assertEquals(60, totals.minutes)
        assertEquals("overnight", 60, totals.projects.single().minutes)
    }

    @Test
    fun `a day with nothing in it is empty rather than absent`() {
        val totals = todayTotals(emptyList(), book, todayKey)

        assertTrue(totals.projects.isEmpty())
        assertEquals(0, totals.rests)
        assertEquals(0, totals.minutes)
        // Still stamped, so a reader can tell "today, nothing yet" from "no
        // snapshot has ever been written".
        assertEquals(todayKey, totals.dateKey)
    }

    @Test
    fun `a snapshot from another day reads as empty rather than as today's`() {
        val yesterday = todayTotals(
            records = listOf(record("a", at(Time.addDays(today, -1), 9), 60)),
            projects = book,
            today = Time.localDateKey(Time.addDays(today, -1)),
        )

        // This is what stops a phone that sat untouched through midnight from
        // showing yesterday's figures as today's.
        val asReadToday = yesterday.forDay(todayKey)
        assertTrue(asReadToday.projects.isEmpty())
        assertEquals(0, asReadToday.rests)
        assertEquals(todayKey, asReadToday.dateKey)

        // ...while on its own day it is still itself.
        assertEquals(60, yesterday.forDay(yesterday.dateKey).minutes)
    }

    @Test
    fun `a record whose project was deleted still counts, under the placeholder`() {
        val totals = todayTotals(
            records = listOf(record("a", at(today, 9), 30, "long-gone")),
            projects = book,
            today = todayKey,
        )

        val row = totals.projects.single()
        assertEquals(30, row.minutes)
        assertEquals(Projects.MISSING_COLOR, row.color)
    }
}
