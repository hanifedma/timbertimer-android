package com.example.timbertimer

import com.example.timbertimer.core.Time
import com.example.timbertimer.core.focusGrowthStage
import com.example.timbertimer.data.RecordMapper
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.RecordStatus
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

    private val noPreference: (String) -> String? = { null }

    // ---------- the running timer ----------

    private fun countdown(startedAt: Long, minutes: Int) = ActiveTimer(
        id = "t1",
        mode = TimerMode.COUNTDOWN,
        title = "Deep focus",
        speciesId = "pine",
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
            speciesId = null,
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

    // ---------- record rules ----------

    @Test
    fun `minutes are clamped into what the table accepts`() {
        assertEquals(25, RecordMapper.cleanMinutes(null, 25, 1))
        assertEquals(1, RecordMapper.cleanMinutes(0, 25, 1))
        assertEquals(0, RecordMapper.cleanMinutes(0, 25, 0))
        assertEquals(600, RecordMapper.cleanMinutes(5000, 25, 1))
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
    fun `an abandoned session always wilts, whatever was chosen`() {
        assertEquals(
            TreeSpecies.WILTED.label,
            RecordMapper.pickTreeKind("study", RecordStatus.ABANDONED, "palm", noPreference),
        )
        assertEquals(
            TreeSpecies.WILTED.label,
            RecordMapper.resolveTreeKind("palm tree", "study", RecordStatus.ABANDONED, noPreference),
        )
    }

    @Test
    fun `an explicit pick wins, and a saved preference beats the default`() {
        assertEquals(
            TreeSpecies.PALM.label,
            RecordMapper.pickTreeKind("study", RecordStatus.COMPLETED, "palm", noPreference),
        )
        assertEquals(
            TreeSpecies.BAMBOO.label,
            RecordMapper.pickTreeKind("study", RecordStatus.COMPLETED, null) { "bamboo" },
        )
    }

    @Test
    fun `a stored species survives normalisation`() {
        val row = FocusSessionRow(
            id = "r1",
            title = "study",
            durationMinutes = 25,
            actualMinutes = 25,
            status = "completed",
            startedAt = "2026-07-31T10:00:00Z",
            endedAt = "2026-07-31T10:25:00Z",
            treeKind = "palm tree",
        )
        // Even with a preference saying otherwise, a planted tree keeps its own.
        assertEquals("palm tree", RecordMapper.normalize(row) { "bamboo" }.treeKind)
    }

    @Test
    fun `a rest is a completed record carrying the wilted tree`() {
        val rest = RecordMapper.normalize(
            FocusSessionRow(
                id = "r2",
                title = Limits.REST_TITLE,
                durationMinutes = 12,
                actualMinutes = 12,
                status = "completed",
                startedAt = "2026-07-31T10:00:00Z",
                endedAt = "2026-07-31T10:12:00Z",
                treeKind = TreeSpecies.WILTED.label,
            ),
            noPreference,
        )
        assertTrue(rest.isRest)
        assertEquals(TreeSpecies.WILTED, rest.species)

        val focus = RecordMapper.normalize(
            FocusSessionRow(
                id = "r3",
                title = "study",
                status = "completed",
                startedAt = "2026-07-31T10:00:00Z",
                endedAt = "2026-07-31T10:25:00Z",
                treeKind = "pine tree",
            ),
            noPreference,
        )
        assertFalse(focus.isRest)
    }

    @Test
    fun `a legacy row with an unknown tree gets a derived one`() {
        val row = FocusSessionRow(
            id = "r4",
            title = "study",
            status = "completed",
            startedAt = "2026-07-31T10:00:00Z",
            treeKind = "young sprout",
        )
        val kind = RecordMapper.normalize(row, noPreference).treeKind
        assertTrue(kind in TreeSpecies.choosable.map { it.label })
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
    fun `the editable timestamp round trips through what the user sees`() {
        val millis = Time.parseEditableTimestamp("2026-07-31 14:05")
        assertEquals("2026-07-31 14:05", Time.editableTimestamp(millis!!))
        assertNull(Time.parseEditableTimestamp("31/07/2026"))
        assertNull(Time.parseEditableTimestamp(""))
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
}
