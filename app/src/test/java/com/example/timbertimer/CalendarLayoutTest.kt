package com.example.timbertimer

import com.example.timbertimer.core.CALENDAR_MIN_MINUTES
import com.example.timbertimer.core.Time
import com.example.timbertimer.core.buildSegments
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.TimerMode
import com.example.timbertimer.data.model.TreeSpecies
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar's layout: which day a record lands on, how a session that runs
 * past midnight is split, and how overlapping blocks share a column's width.
 *
 * These are the parts that are tedious to check by hand on a device and easy to
 * get subtly wrong — a block half an hour out, or two of them stacked on top of
 * each other with one invisible underneath.
 */
class CalendarLayoutTest {

    private val today = Time.startOfDay(System.currentTimeMillis())
    private val days = listOf(Time.addDays(today, -1), today, Time.addDays(today, 1))

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
    fun `a record lands on its own day, at its own hour`() {
        val segments = buildSegments(
            records = listOf(record("a", at(today, 9, 30), 60)),
            timer = null,
            dayStarts = days,
            now = at(today, 12),
        )

        assertEquals(1, segments.size)
        val segment = segments.first()
        assertEquals(1, segment.dayIndex)
        assertEquals(570f, segment.startMin, 0.01f)
        assertEquals(630f, segment.endMin, 0.01f)
        assertEquals(60, segment.minutes)
        assertFalse(segment.partial)
    }

    @Test
    fun `a record outside the visible range is left out entirely`() {
        val segments = buildSegments(
            records = listOf(record("old", at(Time.addDays(today, -10), 9), 60)),
            timer = null,
            dayStarts = days,
            now = at(today, 12),
        )
        assertTrue(segments.isEmpty())
    }

    @Test
    fun `a session across midnight shows in both days and can be dragged in neither`() {
        val segments = buildSegments(
            // 23:00 to 01:00 the next morning.
            records = listOf(record("night", at(today, 23), 120)),
            timer = null,
            dayStarts = days,
            now = at(today, 23),
        ).sortedBy { it.dayIndex }

        assertEquals(2, segments.size)
        assertEquals(1, segments[0].dayIndex)
        assertEquals(1380f, segments[0].startMin, 0.01f)
        assertEquals(1440f, segments[0].endMin, 0.01f)
        assertEquals(60, segments[0].minutes)

        assertEquals(2, segments[1].dayIndex)
        assertEquals(0f, segments[1].startMin, 0.01f)
        assertEquals(60f, segments[1].endMin, 0.01f)
        assertEquals(60, segments[1].minutes)

        // Neither half is a whole block, so neither offers a drag.
        assertTrue(segments.all { it.partial })
    }

    @Test
    fun `a very short record still gets a block big enough to tap`() {
        val segments = buildSegments(
            records = listOf(record("blink", at(today, 9), 1)),
            timer = null,
            dayStarts = days,
            now = at(today, 12),
        )
        val segment = segments.single()
        assertEquals(CALENDAR_MIN_MINUTES.toFloat(), segment.endMin - segment.startMin, 0.01f)
        // The block is padded, but the minutes it reports are the real ones.
        assertEquals(1, segment.minutes)
    }

    @Test
    fun `overlapping records share the day's width, side by side`() {
        val segments = buildSegments(
            records = listOf(
                record("a", at(today, 9), 60),
                record("b", at(today, 9, 30), 60),
                record("c", at(today, 9, 45), 30),
            ),
            timer = null,
            dayStarts = days,
            now = at(today, 12),
        )

        assertEquals(3, segments.size)
        assertTrue(segments.all { it.columns == 3 })
        assertEquals(setOf(0, 1, 2), segments.map { it.column }.toSet())
    }

    @Test
    fun `records that do not overlap each take the whole width`() {
        val segments = buildSegments(
            records = listOf(
                record("morning", at(today, 9), 60),
                record("afternoon", at(today, 14), 60),
            ),
            timer = null,
            dayStarts = days,
            now = at(today, 18),
        )
        assertTrue(segments.all { it.columns == 1 && it.column == 0 })
    }

    @Test
    fun `a freed column is reused rather than widening the whole run`() {
        // a: 09:00-10:00, b: 09:30-10:30, c: 10:15-11:00 — c can sit in a's lane,
        // so the run is two columns wide rather than three.
        val segments = buildSegments(
            records = listOf(
                record("a", at(today, 9), 60),
                record("b", at(today, 9, 30), 60),
                record("c", at(today, 10, 15), 45),
            ),
            timer = null,
            dayStarts = days,
            now = at(today, 12),
        ).associateBy { it.title }

        assertEquals(2, segments.getValue("a").columns)
        assertEquals(0, segments.getValue("a").column)
        assertEquals(1, segments.getValue("b").column)
        assertEquals(0, segments.getValue("c").column)
    }

    @Test
    fun `the running timer appears as a block that fills in live`() {
        val timer = ActiveTimer(
            id = "live",
            mode = TimerMode.STOPWATCH,
            title = "Deep focus",
            projectId = Projects.DEFAULT_ID,
            durationMinutes = 0,
            durationSeconds = 0,
            startedAt = at(today, 10),
            endAt = at(today, 10) + 86_400_000L,
            cloudSynced = false,
        )
        val segments = buildSegments(
            records = emptyList(),
            timer = timer,
            dayStarts = days,
            now = at(today, 10, 40),
        )

        val segment = segments.single()
        assertTrue(segment.running)
        // A running timer is not a record yet, so there is nothing to edit.
        assertEquals(null, segment.record)
        assertEquals(600f, segment.startMin, 0.01f)
        assertEquals(640f, segment.endMin, 0.01f)
    }

    @Test
    fun `an empty range lays nothing out rather than failing`() {
        assertTrue(buildSegments(emptyList(), null, emptyList(), 0L).isEmpty())
        assertTrue(buildSegments(emptyList(), null, days, at(today, 12)).isEmpty())
    }
}
