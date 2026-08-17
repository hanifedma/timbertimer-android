package com.example.timbertimer.data.model

import androidx.annotation.StringRes
import com.example.timbertimer.R

/**
 * The seven species a focus session can plant, plus the wilted tree a rest
 * leaves behind.
 *
 * [label] is what goes into `focus_sessions.tree_kind`, and it is deliberately
 * the English string the web app writes: a record has to mean the same thing on
 * every client and in every language. [displayRes] is display only.
 */
enum class TreeSpecies(val id: String, val label: String, @StringRes val displayRes: Int) {
    CANOPY("canopy", "canopy tree", R.string.tree_canopy),
    PALM("palm", "palm tree", R.string.tree_palm),
    PINE("pine", "pine tree", R.string.tree_pine),
    BAMBOO("bamboo", "bamboo stand", R.string.tree_bamboo),
    FERN("fern", "fern tree", R.string.tree_fern),
    KAPOK("kapok", "kapok tree", R.string.tree_kapok),
    MANGROVE("mangrove", "mangrove tree", R.string.tree_mangrove),

    /** Not offered in the picker; it is what the Rest project grows by default. */
    WILTED("wilted", "wilted sprout", R.string.tree_wilted);

    companion object {
        /** The species a user can actually pick, in the web app's order. */
        val choosable = listOf(CANOPY, PALM, PINE, BAMBOO, FERN, KAPOK, MANGROVE)

        fun byId(id: String?): TreeSpecies? = entries.firstOrNull { it.id == id }

        fun byLabel(label: String?): TreeSpecies? = entries.firstOrNull { it.label == label }

        /** Accepts either form, because older rows stored the id in `tree_kind`. */
        fun byLabelOrId(value: String?): TreeSpecies? = byLabel(value) ?: byId(value)
    }
}

enum class TimerMode(val wire: String) {
    COUNTDOWN("countdown"),
    STOPWATCH("stopwatch");

    companion object {
        fun from(value: String?): TimerMode =
            if (value == STOPWATCH.wire) STOPWATCH else COUNTDOWN
    }
}

/**
 * One planted tree: a row of `public.focus_sessions`.
 *
 * A record is a title, a project, when it ran and for how long. It remembers no
 * goal it was measured against and no outcome it was filed under — the timer's
 * countdown belongs to [ActiveTimer], and ends with it.
 *
 * Times are epoch milliseconds here and ISO-8601 at the database boundary.
 */
data class FocusRecord(
    val id: String,
    val title: String,
    /**
     * Always resolved, never null: a row written before projects existed is
     * mapped to one here, so nothing downstream has to know about two shapes.
     */
    val projectId: String,
    val actualMinutes: Int,
    val startedAt: Long,
    val endedAt: Long,
    val treeKind: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    /** Rest is just a project now, which is what makes rests addable by hand. */
    val isRest: Boolean get() = projectId == Projects.REST_ID

    /** How long the block is: what was actually spent, at least a visible minute. */
    val minutes: Int get() = maxOf(actualMinutes, 0)

    /** The end the calendar draws to, kept consistent with [minutes]. */
    val endsAt: Long get() = maxOf(endedAt, startedAt)

    /**
     * The species written on this record. Only used where the project is
     * unknown — a [ProjectBook] is what decides how a record is actually drawn.
     */
    val storedSpecies: TreeSpecies
        get() = TreeSpecies.byLabelOrId(treeKind) ?: TreeSpecies.PINE
}

/** One row of `public.notes` — an item on the shared to-do list. */
data class Note(
    val id: String,
    val text: String,
    val done: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A focus timer that is currently running.
 *
 * [endAt] is a wall-clock instant rather than a countdown that has to be
 * decremented, so the remaining time is always recomputed from the clock. That
 * is what makes the timer survive the process being killed, the device
 * sleeping, and even a reboot: nothing has to keep ticking for it to stay right.
 */
data class ActiveTimer(
    val id: String,
    val mode: TimerMode,
    val title: String,
    /** The project this session will be filed under, and whose tree it grows. */
    val projectId: String,
    val durationMinutes: Int,
    val durationSeconds: Int,
    val startedAt: Long,
    val endAt: Long,
    /** True once the row exists in `active_focus_timers`, which the completion
     *  hand-off relies on to know whether another device might also be running it. */
    val cloudSynced: Boolean,
) {
    fun remainingSeconds(now: Long = System.currentTimeMillis()): Long =
        if (mode == TimerMode.STOPWATCH) 0L
        else maxOf(0L, ceilDiv(endAt - now, 1000L))

    fun elapsedSeconds(now: Long = System.currentTimeMillis()): Long =
        if (mode == TimerMode.STOPWATCH) maxOf(0L, (now - startedAt) / 1000L)
        else maxOf(0L, durationSeconds - remainingSeconds(now))

    /** Countdown progress 0..1; a stopwatch has no goal, so it reports 0. */
    fun progress(now: Long = System.currentTimeMillis()): Float =
        if (mode == TimerMode.STOPWATCH || durationSeconds <= 0) 0f
        else (1f - remainingSeconds(now).toFloat() / durationSeconds).coerceIn(0f, 1f)

    fun isDue(now: Long = System.currentTimeMillis()): Boolean =
        mode == TimerMode.COUNTDOWN && remainingSeconds(now) <= 0L

    private companion object {
        /** Matches JS `Math.ceil(ms / 1000)` for negative values too. */
        fun ceilDiv(value: Long, by: Long): Long =
            if (value <= 0L) value / by else (value + by - 1) / by
    }
}

/**
 * A rest that is currently running — either open-ended, or counting down to a
 * length the user picked.
 *
 * [endAt] is what tells the two apart, and it is an instant for the same reason
 * [ActiveTimer.endAt] is: nothing has to keep ticking for a rest to stay
 * correct, so one whose moment passed while the phone was off is simply *due*.
 * A null [endAt] is the original open-ended stopwatch, which has no moment to
 * arrive and so never alarms.
 *
 * It records nothing until it is stopped.
 */
data class RestTimer(
    val startedAt: Long,
    /** Null for the open-ended stopwatch; the instant a countdown lands on. */
    val endAt: Long? = null,
    /** The length that was picked, in minutes. 0 for the stopwatch. */
    val durationMinutes: Int = 0,
    /**
     * True once the row exists in `active_rest_timers`.
     *
     * The same distinction [ActiveTimer.cloudSynced] draws, and needed for the
     * same reason: an empty shared row means "another device finished this" only
     * for a rest that was published in the first place. Without it, a rest
     * started with no signal is indistinguishable from one that has been ended
     * elsewhere, and the next sync throws it away — taking its alarm with it.
     */
    val cloudSynced: Boolean = false,
) {
    val isCountdown: Boolean get() = endAt != null

    /** How long this rest has actually run — the figure that gets recorded. */
    fun elapsedSeconds(now: Long = System.currentTimeMillis()): Long =
        maxOf(0L, (now - startedAt) / 1000L)

    /** Seconds still to go, or 0 for a stopwatch, which has nowhere to go. */
    fun remainingSeconds(now: Long = System.currentTimeMillis()): Long =
        if (endAt == null) 0L else maxOf(0L, ceilDiv(endAt - now, 1000L))

    /** The clock the panel shows: counting down when there is something to count to. */
    fun displaySeconds(now: Long = System.currentTimeMillis()): Long =
        if (endAt == null) elapsedSeconds(now) else remainingSeconds(now)

    /** Countdown progress 0..1. A stopwatch has no goal, so it reports 0. */
    fun progress(now: Long = System.currentTimeMillis()): Float {
        val total = totalSeconds
        if (total <= 0L) return 0f
        return (1f - remainingSeconds(now).toFloat() / total).coerceIn(0f, 1f)
    }

    fun isDue(now: Long = System.currentTimeMillis()): Boolean =
        endAt != null && remainingSeconds(now) <= 0L

    /**
     * The countdown's full length, taken from the two instants rather than from
     * [durationMinutes]: a rest adopted from another device is only guaranteed
     * to carry its endpoints, and the ends are what the progress bar measures.
     */
    private val totalSeconds: Long
        get() = if (endAt == null) 0L else maxOf(0L, (endAt - startedAt) / 1000L)

    private companion object {
        /** Matches JS `Math.ceil(ms / 1000)` for negative values too. */
        fun ceilDiv(value: Long, by: Long): Long =
            if (value <= 0L) value / by else (value + by - 1) / by
    }
}

/** Where records are being read from and written to right now. */
enum class DataMode { LOCAL, CLOUD }

object Limits {
    /** `focus_sessions.title` is `char_length(title) between 1 and 80`. */
    const val TITLE_MAX = 80

    /** `notes.text` is `char_length(text) between 1 and 500`. */
    const val NOTE_MAX = 500

    /**
     * A record may now span at most one day. The calendar edits real start and
     * end times, and the table's CHECK constraints allow the same ceiling.
     */
    const val MINUTES_MAX = 1440

    /** The longest countdown the timer form offers — a day-long one is a mistake. */
    const val TIMER_MINUTES_MAX = 600

    const val DEFAULT_DURATION = 30

    /**
     * The rest shortcuts, and the length one starts on.
     *
     * Shared with the web app so a rest set on one client reads the same on the
     * other. Ten minutes rather than five: it is the one most people actually
     * want, and the shorter shortcut is right there if they do not.
     */
    val REST_PRESETS = listOf(5, 10, 15)
    const val DEFAULT_REST_DURATION = 10

    /**
     * Stored untranslated, exactly as the web app writes them, so a record reads
     * the same on every client.
     */
    const val DEFAULT_TITLE = "Deep focus"
    const val REST_TITLE = "Rest"

    /** A stopwatch parks its end 24h out, which is also the DB's seconds cap. */
    const val STOPWATCH_SECONDS = 86400
}
