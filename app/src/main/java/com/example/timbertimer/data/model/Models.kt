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

/** The rest stopwatch. It records nothing until it is stopped. */
data class RestTimer(val startedAt: Long) {
    fun elapsedSeconds(now: Long = System.currentTimeMillis()): Long =
        maxOf(0L, (now - startedAt) / 1000L)
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

    const val DEFAULT_DURATION = 25

    /**
     * Stored untranslated, exactly as the web app writes them, so a record reads
     * the same on every client.
     */
    const val DEFAULT_TITLE = "Deep focus"
    const val REST_TITLE = "Rest"

    /** A stopwatch parks its end 24h out, which is also the DB's seconds cap. */
    const val STOPWATCH_SECONDS = 86400
}
