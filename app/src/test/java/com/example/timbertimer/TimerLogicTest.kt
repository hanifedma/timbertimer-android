package com.example.timbertimer

import com.example.timbertimer.core.Time
import com.example.timbertimer.core.focusGrowthStage
import com.example.timbertimer.core.lastActivityEndedAt
import com.example.timbertimer.data.RecordMapper
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.RestTimer
import com.example.timbertimer.data.model.TimerMode
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.data.remote.FocusSessionRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

/** The timer's arithmetic, the record rules, and the calendar boundaries. */
class TimerLogicTest {

    // ---------- the running timer ----------

    private fun countdown(startedAt: Long, minutes: Int) = ActiveTimer(
        id = "t1",
        mode = TimerMode.COUNTDOWN,
        title = "Deep focus",
        projectId = Projects.DEFAULT_ID,
        durationMinutes = minutes,
        durationSeconds = minutes * 60,
        startedAt = startedAt,
        endAt = startedAt + minutes * 60_000L,
        cloudSynced = false,
    )

    @Test
    fun `remaining time is derived from the clock, not counted down`() {
        val start = 1_000_000_000_000L
        val timer = countdown(start, 25)

        assertEquals(1500L, timer.remainingSeconds(start))
        assertEquals(1499L, timer.remainingSeconds(start + 1_000))
        assertEquals(0L, timer.remainingSeconds(start + 1_500_000))
        // Long past its end — still zero, never negative.
        assertEquals(0L, timer.remainingSeconds(start + 9_999_999))
    }

    @Test
    fun `a timer whose moment passed while the app was closed is due`() {
        val start = 1_000_000_000_000L
        val timer = countdown(start, 25)
        assertFalse(timer.isDue(start + 60_000))
        assertTrue(timer.isDue(start + 1_500_000))
        // This is the reboot case: hours later, it is simply due.
        assertTrue(timer.isDue(start + 86_400_000))
    }

    @Test
    fun `elapsed and progress track the countdown`() {
        val start = 1_000_000_000_000L
        val timer = countdown(start, 10)

        assertEquals(0L, timer.elapsedSeconds(start))
        assertEquals(300L, timer.elapsedSeconds(start + 300_000))
        assertEquals(0f, timer.progress(start), 0.001f)
        assertEquals(0.5f, timer.progress(start + 300_000), 0.001f)
        assertEquals(1f, timer.progress(start + 600_000), 0.001f)
        // Progress is clamped, so an overshoot cannot push the ring past full.
        assertEquals(1f, timer.progress(start + 900_000), 0.001f)
    }

    @Test
    fun `a stopwatch counts up and has no goal to show`() {
        val start = 1_000_000_000_000L
        val timer = ActiveTimer(
            id = "t2",
            mode = TimerMode.STOPWATCH,
            title = "Deep focus",
            projectId = Projects.DEFAULT_ID,
            durationMinutes = 0,
            durationSeconds = 0,
            startedAt = start,
            endAt = start + Limits.STOPWATCH_SECONDS * 1000L,
            cloudSynced = false,
        )

        assertEquals(90L, timer.elapsedSeconds(start + 90_000))
        assertEquals(0f, timer.progress(start + 90_000), 0.001f)
        // A stopwatch never becomes "due" — only the user ends it.
        assertFalse(timer.isDue(start + 90_000))
        assertFalse(timer.isDue(start + Limits.STOPWATCH_SECONDS * 1000L + 1))
    }

    // ---------- the rest ----------

    private fun restCountdown(startedAt: Long, minutes: Int) = RestTimer(
        startedAt = startedAt,
        endAt = startedAt + minutes * 60_000L,
        durationMinutes = minutes,
    )

    @Test
    fun `a rest countdown reads its remaining time from the clock`() {
        val start = 1_000_000_000_000L
        val rest = restCountdown(start, 15)

        assertEquals(900L, rest.remainingSeconds(start))
        assertEquals(899L, rest.remainingSeconds(start + 1_000))
        assertEquals(0L, rest.remainingSeconds(start + 900_000))
        // Never negative, however long the phone was off.
        assertEquals(0L, rest.remainingSeconds(start + 86_400_000))
    }

    @Test
    fun `a rest countdown that ran out while the phone was off is simply due`() {
        val start = 1_000_000_000_000L
        val rest = restCountdown(start, 10)

        assertFalse(rest.isDue(start))
        assertFalse(rest.isDue(start + 599_000))
        assertTrue(rest.isDue(start + 600_000))
        // The reboot case: the alarm is owed, not lost.
        assertTrue(rest.isDue(start + 86_400_000))
    }

    @Test
    fun `the open-ended rest never becomes due`() {
        val start = 1_000_000_000_000L
        val rest = RestTimer(startedAt = start)

        assertFalse(rest.isCountdown)
        assertFalse(rest.isDue(start + 86_400_000))
        // It counts up, and has no goal to measure progress against.
        assertEquals(3600L, rest.elapsedSeconds(start + 3_600_000))
        assertEquals(0L, rest.remainingSeconds(start + 3_600_000))
        assertEquals(0f, rest.progress(start + 3_600_000), 0.001f)
    }

    @Test
    fun `a rest shows the clock its mode calls for`() {
        val start = 1_000_000_000_000L
        // A countdown shows what is left...
        assertEquals(600L, restCountdown(start, 15).displaySeconds(start + 300_000))
        // ...and a stopwatch shows what has gone.
        assertEquals(300L, RestTimer(startedAt = start).displaySeconds(start + 300_000))
    }

    /**
     * Measured between the two instants rather than from [RestTimer.durationMinutes],
     * because a rest adopted from another device is only guaranteed to carry its
     * endpoints — and the bar has to be right for one of those too.
     */
    @Test
    fun `rest progress is measured between the two instants`() {
        val start = 1_000_000_000_000L
        val rest = restCountdown(start, 10)

        assertEquals(0f, rest.progress(start), 0.001f)
        assertEquals(0.5f, rest.progress(start + 300_000), 0.001f)
        assertEquals(1f, rest.progress(start + 600_000), 0.001f)
        // Past the end it stays pinned rather than running over.
        assertEquals(1f, rest.progress(start + 900_000), 0.001f)

        // A row that lost its duration_minutes to an un-migrated database still
        // draws a correct bar, because the ends are all it reads.
        val endsOnly = RestTimer(startedAt = start, endAt = start + 600_000, durationMinutes = 0)
        assertEquals(0.5f, endsOnly.progress(start + 300_000), 0.001f)
    }

    @Test
    fun `an extended rest keeps its start and moves only its end`() {
        val start = 1_000_000_000_000L
        val rest = restCountdown(start, 10)
        // What TimerEngine.extendRest builds when the rest has not run out yet.
        val extended = rest.copy(
            endAt = rest.endAt!! + 5 * 60_000L,
            durationMinutes = rest.durationMinutes + 5,
        )

        assertEquals(start, extended.startedAt)
        assertEquals(900L, extended.remainingSeconds(start))
        assertFalse(extended.isDue(start + 600_000))
        assertTrue(extended.isDue(start + 900_000))
    }

    /**
     * The rule `finishRest` applies: a rest that reached its end is credited in
     * full, so a second of rounding slack cannot turn a 15-minute rest into 14.
     */
    @Test
    fun `a rest that ran out is credited for its whole length`() {
        val start = 1_000_000_000_000L
        val rest = restCountdown(start, 15)

        // The tick that notices lands a moment late, as it always does.
        val elapsed = rest.elapsedSeconds(start + 900_400)
        assertTrue(elapsed >= rest.durationMinutes * 60L - 1)

        // Ended early, it is worth only what it actually ran.
        val early = rest.elapsedSeconds(start + 240_000)
        assertFalse(early >= rest.durationMinutes * 60L - 1)
        assertEquals(4, Math.round(early / 60.0).toInt())
    }

    // ---------- record rules ----------

    @Test
    fun `minutes are clamped into what the table accepts`() {
        assertEquals(25, RecordMapper.cleanMinutes(null, 25, 1))
        assertEquals(1, RecordMapper.cleanMinutes(0, 25, 1))
        assertEquals(0, RecordMapper.cleanMinutes(0, 25, 0))
        // A record may now span a whole day, which is where the ceiling sits.
        assertEquals(1440, RecordMapper.cleanMinutes(5000, 25, 1))
        assertEquals(42, RecordMapper.cleanMinutes(42, 25, 1))
    }

    @Test
    fun `a title too long for the column is cut rather than rejected`() {
        val long = "x".repeat(200)
        assertEquals(Limits.TITLE_MAX, RecordMapper.cleanTitle(long).length)
        assertEquals(Limits.DEFAULT_TITLE, RecordMapper.cleanTitle("   "))
        assertEquals(Limits.DEFAULT_TITLE, RecordMapper.cleanTitle(null))
        assertEquals("study", RecordMapper.cleanTitle("  study  "))
    }

    @Test
    fun `a session plants whatever its project grows`() {
        assertEquals(
            TreeSpecies.PALM.label,
            RecordMapper.pickTreeKind(project("work", TreeSpecies.PALM.id)),
        )
        // Rest legitimately grows the wilted sprout, which is not a "choosable"
        // species — it still has to come through.
        assertEquals(
            TreeSpecies.WILTED.label,
            RecordMapper.pickTreeKind(Projects.builtIn(Projects.REST_ID, 0L)),
        )
        assertEquals(TreeSpecies.WILTED.label, RecordMapper.resolveTreeKind("wilted sprout"))
        // A species this build does not know is redrawn from its project anyway.
        assertEquals(TreeSpecies.PINE.label, RecordMapper.resolveTreeKind("young sprout"))
    }

    @Test
    fun `a stored species survives normalisation`() {
        val row = FocusSessionRow(
            id = "r1",
            title = "study",
            projectId = "p1",
            actualMinutes = 25,
            startedAt = "2026-07-31T10:00:00Z",
            endedAt = "2026-07-31T10:25:00Z",
            treeKind = "palm tree",
        )
        assertEquals("palm tree", RecordMapper.normalize(row).treeKind)
        assertEquals("p1", RecordMapper.normalize(row).projectId)
    }

    // ---------- projects ----------

    @Test
    fun `a row that carries a project keeps it`() {
        assertEquals("t:anything", Projects.resolveId("t:anything", null, "pine tree", "whatever"))
    }

    @Test
    fun `a record written before projects existed is mapped by its shape`() {
        // A wilted tree that was not abandoned was a rest.
        assertEquals(
            Projects.REST_ID,
            Projects.resolveId(null, "completed", TreeSpecies.WILTED.label, "Rest"),
        )
        // Including a row that carries no status at all, which is every row the
        // database has had since the column was dropped.
        assertEquals(
            Projects.REST_ID,
            Projects.resolveId(null, null, TreeSpecies.WILTED.label, "Rest"),
        )
        // Anything else keys off its title, case- and space-insensitively.
        assertEquals("t:deep focus", Projects.resolveId(null, null, "pine tree", "  Deep Focus "))
        assertEquals("t:deep focus", Projects.resolveId(null, null, "pine tree", ""))
        // A session this device saved as abandoned wilted too, but it was never
        // a rest — which is the one thing that old column is still read for.
        assertEquals(
            "t:study",
            Projects.resolveId(null, "abandoned", TreeSpecies.WILTED.label, "study"),
        )
        // A blank id is not an id.
        assertEquals("t:study", Projects.resolveId("  ", null, "pine tree", "study"))
    }

    @Test
    fun `the two built-ins are what a fresh install starts with`() {
        val focus = Projects.builtIn(Projects.DEFAULT_ID, 0L)
        val rest = Projects.builtIn(Projects.REST_ID, 0L)
        assertEquals(Projects.COLORS[0], focus.color)
        assertEquals(TreeSpecies.PINE.id, focus.tree)
        assertEquals(Projects.REST_COLOR, rest.color)
        assertEquals(TreeSpecies.WILTED.id, rest.tree)
        assertTrue(focus.isBuiltIn && rest.isBuiltIn)
    }

    @Test
    fun `Focus sorts first and Rest last, whatever they are called`() {
        val order = Projects.sorted(
            listOf(
                project("zebra"),
                Projects.builtIn(Projects.REST_ID, 0L),
                project("apple"),
                Projects.builtIn(Projects.DEFAULT_ID, 0L),
            )
        ).map { it.id }
        assertEquals(Projects.DEFAULT_ID, order.first())
        assertEquals(Projects.REST_ID, order.last())
        assertEquals(listOf("apple", "zebra"), order.subList(1, 3))
    }

    @Test
    fun `a record whose project was deleted still renders, in neutral grey`() {
        val book = ProjectBook(listOf(Projects.builtIn(Projects.DEFAULT_ID, 0L)))
        val missing = book["gone"]
        assertTrue(missing.missing)
        assertEquals(Projects.MISSING_COLOR, missing.color)

        // And it falls back to the species written on the record itself.
        val record = record(projectId = "gone", treeKind = TreeSpecies.KAPOK.label)
        assertEquals(TreeSpecies.KAPOK, book.speciesFor(record))
    }

    @Test
    fun `the project decides how a record is drawn, so recolouring re-plants it`() {
        val book = ProjectBook(listOf(project("work", tree = TreeSpecies.BAMBOO.id)))
        // The record was planted as a pine; its project now grows bamboo.
        val record = record(projectId = "work", treeKind = TreeSpecies.PINE.label)
        assertEquals(TreeSpecies.BAMBOO, book.speciesFor(record))
    }

    @Test
    fun `a rest is any record filed under the Rest project`() {
        assertTrue(record(projectId = Projects.REST_ID).isRest)
        assertFalse(record(projectId = Projects.DEFAULT_ID).isRest)
        // Including one added by hand that grows something other than a sprout.
        assertTrue(record(projectId = Projects.REST_ID, treeKind = TreeSpecies.PALM.label).isRest)
    }

    @Test
    fun `a project name is trimmed and capped to what the column accepts`() {
        val project = Projects.normalize(
            id = "p",
            name = "  " + "x".repeat(200) + "  ",
            color = "nonsense",
            tree = "not a tree",
            sortOrder = 0,
            createdAt = 0L,
            updatedAt = 0L,
        )
        assertEquals(Projects.NAME_MAX, project.name.length)
        // An unusable colour and tree fall back to the ones the name would pick.
        assertEquals(Projects.colorForName(project.name), project.color)
        assertEquals(Projects.treeForName(project.name), project.tree)
    }

    // ---------- how long the forest has been still ----------

    private fun ended(id: String, startedAt: Long, minutes: Int, projectId: String = Projects.DEFAULT_ID) =
        com.example.timbertimer.data.model.FocusRecord(
            id = id,
            title = id,
            projectId = projectId,
            actualMinutes = minutes,
            startedAt = startedAt,
            endedAt = startedAt + minutes * 60_000L,
            treeKind = TreeSpecies.PINE.label,
            createdAt = startedAt,
            updatedAt = startedAt,
        )

    @Test
    fun `a rest counts as time spent, so it resets the idle clock`() {
        val now = 1_000_000_000_000L
        val focus = ended("focus", now - 3 * 60 * 60_000L, 25)
        val rest = ended("rest", now - 40 * 60_000L, 10, Projects.REST_ID)

        // The rest ended half an hour ago; the focus session, three hours.
        assertEquals(now - 30 * 60_000L, lastActivityEndedAt(listOf(focus, rest), now))
        // And on its own it still answers, rather than reading as "never".
        assertEquals(now - 30 * 60_000L, lastActivityEndedAt(listOf(rest), now))
    }

    @Test
    fun `a session the calendar has only planned does not count`() {
        val now = 1_000_000_000_000L
        val done = ended("done", now - 90 * 60_000L, 30)
        val planned = ended("planned", now + 60 * 60_000L, 30)

        assertEquals(now - 60 * 60_000L, lastActivityEndedAt(listOf(done, planned), now))
        assertNull(lastActivityEndedAt(listOf(planned), now))
    }

    @Test
    fun `a session that has only just finished reads as now, not as the future`() {
        val now = 1_000_000_000_000L
        // Started two seconds ago and rounded up to a whole minute, so its end
        // is 58 seconds away. It must still count, and must not run ahead.
        val justNow = ended("just now", now - 2_000L, 1)

        assertEquals(now, lastActivityEndedAt(listOf(justNow), now))
    }

    @Test
    fun `an empty forest has never been anything but still`() {
        assertNull(lastActivityEndedAt(emptyList(), 1_000_000_000_000L))
    }

    // ---------- time ----------

    @Test
    fun `the clock reads mm ss, widening past an hour`() {
        assertEquals("00:00", Time.formatClock(0))
        assertEquals("00:09", Time.formatClock(9))
        assertEquals("25:00", Time.formatClock(1500))
        assertEquals("59:59", Time.formatClock(3599))
        assertEquals("1:00:00", Time.formatClock(3600))
        assertEquals("2:05:03", Time.formatClock(7503))
        // Negative input cannot happen, but must not print as garbage if it did.
        assertEquals("00:00", Time.formatClock(-5))
    }

    @Test
    fun `durations read the way the website prints them`() {
        assertEquals("0m", Time.formatMinutes(0, "m", "h"))
        assertEquals("45m", Time.formatMinutes(45, "m", "h"))
        assertEquals("1h", Time.formatMinutes(60, "m", "h"))
        assertEquals("1h 30m", Time.formatMinutes(90, "m", "h"))
        assertEquals("2h", Time.formatMinutes(120, "m", "h"))
        assertEquals("10h 1m", Time.formatMinutes(601, "m", "h"))
    }

    @Test
    fun `weeks start on Sunday, as they do in JavaScript`() {
        // 2026-07-31 is a Friday; its week starts Sunday 2026-07-26.
        val friday = Instant.parse("2026-07-31T12:00:00Z").toEpochMilli()
        val start = Time.startOfWeek(friday)
        val startDate = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()

        assertEquals(DayOfWeek.SUNDAY, startDate.dayOfWeek)
        assertTrue(start <= friday)
        assertTrue(Time.addDays(start, 7) > friday)
        // A Sunday is already the start of its own week.
        assertEquals(start, Time.startOfWeek(start))
    }

    @Test
    fun `a month runs from the first to the first`() {
        val midMonth = Instant.parse("2026-07-31T12:00:00Z").toEpochMilli()
        val start = Time.startOfMonth(midMonth)
        val date = Instant.ofEpochMilli(start).atZone(ZoneId.systemDefault()).toLocalDate()

        assertEquals(1, date.dayOfMonth)
        assertEquals(7, date.monthValue)
        val next = Time.startOfMonth(Time.addMonths(start, 1))
        assertEquals(8, Instant.ofEpochMilli(next).atZone(ZoneId.systemDefault()).toLocalDate().monthValue)
    }

    @Test
    fun `both timestamp shapes Postgres and the web client write will parse`() {
        val withZ = Time.parseIso("2026-07-31T10:00:00Z")
        val withOffset = Time.parseIso("2026-07-31T10:00:00+00:00")
        val withMillis = Time.parseIso("2026-07-31T10:00:00.123Z")

        assertEquals(withZ, withOffset)
        assertEquals(withZ!! + 123, withMillis)
        assertNull(Time.parseIso(null))
        assertNull(Time.parseIso(""))
        assertNull(Time.parseIso("not a timestamp"))
    }

    @Test
    fun `an instant survives the round trip to the database and back`() {
        val millis = Instant.parse("2026-07-31T10:00:00.250Z").toEpochMilli()
        assertEquals(millis, Time.parseIso(Time.toIso(millis)))
    }

    @Test
    fun `minutes into the day are measured against local midnight`() {
        val start = Time.startOfDay(System.currentTimeMillis())
        assertEquals(0, Time.minutesIntoDay(start))
        assertEquals(90, Time.minutesIntoDay(start + 90 * 60_000L))
        assertEquals(1439, Time.minutesIntoDay(start + 1439 * 60_000L))
        // Always measured against the instant's *own* midnight, so nothing can
        // ever be placed off the bottom of the grid.
        assertTrue(Time.minutesIntoDay(start + 40 * 3_600_000L) in 0..1440)
        assertTrue(Time.minutesIntoDay(start - 3_600_000L) in 0..1440)
    }

    @Test
    fun `the date picker's UTC midnight is read back as the same local day`() {
        val millis = Instant.parse("2026-07-31T22:30:00Z").toEpochMilli()
        val utcDate = Time.toUtcDateMillis(millis)
        // Re-applying the day the picker reports must not move the record.
        assertEquals(
            Time.localDateKey(millis),
            Time.localDateKey(Time.withDateFromUtcMillis(millis, utcDate)),
        )
        assertEquals(
            Time.hourOf(millis) to Time.minuteOf(millis),
            Time.hourOf(Time.withDateFromUtcMillis(millis, utcDate)) to
                Time.minuteOf(Time.withDateFromUtcMillis(millis, utcDate)),
        )
    }

    @Test
    fun `setting a time keeps the day and drops the stray seconds`() {
        val millis = Instant.parse("2026-07-31T10:00:33.500Z").toEpochMilli()
        val moved = Time.withTime(millis, 14, 5)
        assertEquals(14, Time.hourOf(moved))
        assertEquals(5, Time.minuteOf(moved))
        assertEquals(0L, moved % 60_000L)
        assertEquals(Time.localDateKey(millis), Time.localDateKey(moved))
    }

    @Test
    fun `growth stages step at the web client's boundaries`() {
        assertEquals(0, focusGrowthStage(0))
        assertEquals(0, focusGrowthStage(15))
        assertEquals(1, focusGrowthStage(16))
        assertEquals(1, focusGrowthStage(30))
        assertEquals(2, focusGrowthStage(31))
        assertEquals(2, focusGrowthStage(45))
        assertEquals(3, focusGrowthStage(46))
        assertEquals(3, focusGrowthStage(600))
    }

    // ---------- fixtures ----------

    private fun project(id: String, tree: String = TreeSpecies.PINE.id) = Project(
        id = id,
        name = id,
        color = Projects.colorForName(id),
        tree = tree,
        sortOrder = 1,
        createdAt = 0L,
        updatedAt = 0L,
    )

    private fun record(
        projectId: String,
        treeKind: String = TreeSpecies.PINE.label,
    ) = com.example.timbertimer.data.model.FocusRecord(
        id = "r",
        title = "study",
        projectId = projectId,
        actualMinutes = 25,
        startedAt = 0L,
        endedAt = 25 * 60_000L,
        treeKind = treeKind,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
