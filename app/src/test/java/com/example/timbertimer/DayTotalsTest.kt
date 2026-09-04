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

        // The notification says both numbers, and they answer different
        // questions: three short breaks and one long afternoon are both "3
        // rests" until the time is said out loud.
        assertEquals(20, totals.restMinutes)
        // Focus is not rest, however much of it there was.
        assertEquals(70, totals.minutes)
    }

    @Test
    fun `a day with focus but no rest reports no rest time`() {
        val totals = todayTotals(
            records = listOf(record("a", at(today, 9), 45)),
            projects = book,
            today = todayKey,
        )

        // Zero rather than the focus figure, and zero rather than a crash on a
        // Rest row that is simply not there.
        assertEquals(0, totals.rests)
        assertEquals(0, totals.restMinutes)
        assertEquals(45, totals.minutes)
    }

    @Test
    fun `a record counts on the day it started, not the day it ended`() {
        val totals = todayTotals(
            records = listOf(
                record("yesterday", at(Time.addDays(today, -1), 9), 60),
                // 23:30 last night through 00:30 this morning. The user sat
                // down for it yesterday, so all sixty minutes are yesterday's —
                // none of it leaks into today.
                record("overnight", at(Time.addDays(today, -1), 23, 30), 60),
                // And the mirror image: begun at 23:40 tonight, finishing after
                // midnight, and counted here in full.
                record("tonight", at(today, 23, 40), 60),
                record("tomorrow", at(Time.addDays(today, 1), 9), 60),
            ),
            projects = book,
            today = todayKey,
        )

        assertEquals(60, totals.minutes)
        assertEquals("tonight", 60, totals.projects.single().minutes)
    }

    @Test
    fun `an overnight rest is counted once, on the evening it began`() {
        val yesterdayStart = Time.addDays(today, -1)
        val records = listOf(record("nap", at(yesterdayStart, 23, 45), 30, Projects.REST_ID))

        val tonight = todayTotals(records, book, Time.localDateKey(yesterdayStart))
        val morning = todayTotals(records, book, todayKey)

        // The awkward one: a rest that crosses midnight used to be tallied on
        // the far side of it, which both moved the minutes and moved the count
        // the standing notification reads.
        assertEquals(1, tonight.rests)
        assertEquals(30, tonight.restMinutes)
        assertEquals(0, morning.rests)
        assertEquals(0, morning.restMinutes)
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
