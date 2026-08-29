package com.example.timbertimer.data.local

import android.content.Context
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.remote.FocusSessionRow
import com.example.timbertimer.data.remote.NoteRow
import com.example.timbertimer.data.remote.ProjectRow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The offline half of the app — the direct counterpart of the web client's
 * localStorage. Everything here works with no account at all, and a signed-in
 * user still gets a local copy so records survive a dead network.
 *
 * SharedPreferences rather than DataStore because the running timer has to be
 * readable *synchronously*: the foreground service and the boot receiver both
 * need it before any coroutine could deliver a result, and a timer that is one
 * frame late to reappear looks like a timer that was lost.
 */
class LocalStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ---------- focus records ----------

    fun readSessions(): List<FocusSessionRow> = read(KEY_SESSIONS, FocusSessionRow.serializer())

    fun writeSessions(rows: List<FocusSessionRow>) = write(KEY_SESSIONS, FocusSessionRow.serializer(), rows)

    // ---------- signed-in mirror ----------

    /**
     * The last records and notes seen in the cloud, so a signed-in user opening
     * the app on a plane still sees their forest instead of an empty one.
     *
     * Kept apart from the local-only lists above and stamped with the user id:
     * one account's records must never surface as another's, or as "local"
     * records after signing out.
     */
    fun readCloudCache(userId: String): List<FocusSessionRow> =
        if (cachedUserId() == userId) read(KEY_CLOUD_SESSIONS, FocusSessionRow.serializer())
        else emptyList()

    fun readCloudNotes(userId: String): List<NoteRow> =
        if (cachedUserId() == userId) read(KEY_CLOUD_NOTES, NoteRow.serializer()) else emptyList()

    fun writeCloudCache(userId: String, sessions: List<FocusSessionRow>) {
        prefs.edit().putString(KEY_CLOUD_USER, userId).apply()
        write(KEY_CLOUD_SESSIONS, FocusSessionRow.serializer(), sessions)
    }

    fun writeCloudNotes(userId: String, notes: List<NoteRow>) {
        prefs.edit().putString(KEY_CLOUD_USER, userId).apply()
        write(KEY_CLOUD_NOTES, NoteRow.serializer(), notes)
    }

    fun clearCloudCache() {
        prefs.edit()
            .remove(KEY_CLOUD_USER)
            .remove(KEY_CLOUD_SESSIONS)
            .remove(KEY_CLOUD_NOTES)
            .remove(KEY_CLOUD_PROJECTS)
            .remove(KEY_PENDING)
            .apply()
    }

    private fun cachedUserId(): String? = prefs.getString(KEY_CLOUD_USER, null)

    /**
     * Records that finished while the upload could not go through.
     *
     * A session the user actually sat through is the one thing this app must
     * never drop, so it is parked here and retried on the next refresh instead
     * of being reported as saved and quietly lost.
     */
    fun readPending(userId: String): List<FocusSessionRow> =
        if (cachedUserId() == userId) read(KEY_PENDING, FocusSessionRow.serializer())
        else emptyList()

    fun writePending(userId: String, rows: List<FocusSessionRow>) {
        prefs.edit().putString(KEY_CLOUD_USER, userId).apply()
        write(KEY_PENDING, FocusSessionRow.serializer(), rows)
    }

    // ---------- projects ----------

    /**
     * The projects a signed-out user has made on this device.
     *
     * Kept apart from the signed-in mirror below for the same reason records
     * are: project names are the user's own words, and one account's must never
     * surface under another's, or as "local" projects after signing out.
     */
    fun readProjects(): List<ProjectRow> = read(KEY_PROJECTS, ProjectRow.serializer())

    fun writeProjects(rows: List<ProjectRow>) = write(KEY_PROJECTS, ProjectRow.serializer(), rows)

    /** The last projects seen in the cloud, so an offline start still has colours. */
    fun readCloudProjects(userId: String): List<ProjectRow> =
        if (cachedUserId() == userId) read(KEY_CLOUD_PROJECTS, ProjectRow.serializer())
        else emptyList()

    fun writeCloudProjects(userId: String, rows: List<ProjectRow>) {
        prefs.edit().putString(KEY_CLOUD_USER, userId).apply()
        write(KEY_CLOUD_PROJECTS, ProjectRow.serializer(), rows)
    }

    // ---------- to-do ----------

    fun readNotes(): List<NoteRow> = read(KEY_NOTES, NoteRow.serializer())

    fun writeNotes(rows: List<NoteRow>) = write(KEY_NOTES, NoteRow.serializer(), rows)

    /**
     * The per-device fallback ordering, kept for the case where the Supabase
     * project has no `sort_order` column yet.
     */
    fun readNotesOrder(): List<String> = read(KEY_NOTES_ORDER, String.serializer())

    fun writeNotesOrder(ids: List<String>) = write(KEY_NOTES_ORDER, String.serializer(), ids)

    // ---------- home screen widget ----------

    /**
     * A flat copy of the to-do list for the widget to render.
     *
     * The widget's list adapter can run in a process that was started for it
     * alone, before any account has been resolved — so it reads this snapshot
     * rather than working out whether the real list lives locally or in the
     * cloud. Whichever one is authoritative writes it here.
     */
    fun readWidgetNotes(): List<WidgetNote> = read(KEY_WIDGET_NOTES, WidgetNote.serializer())

    fun writeWidgetNotes(notes: List<WidgetNote>) =
        write(KEY_WIDGET_NOTES, WidgetNote.serializer(), notes)

    // ---------- today's totals ----------

    /**
     * What today added up to, per project, plus how many rests it held.
     *
     * Written for the same reason [readWidgetNotes] is: the widget's adapter and
     * the notification restorer can both run in a process started for them
     * alone, where the repository has not loaded a single record yet. Reading a
     * plain snapshot is synchronous and cannot come back empty just because a
     * coroutine has not finished.
     *
     * [TodayTotals.dateKey] is stored with it so a snapshot is never mistaken
     * for a fresher one than it is: the day can turn over while the phone sits
     * on a table with nothing to write, and yesterday's figures must read as
     * yesterday's rather than as today's.
     */
    fun readTodayTotals(): TodayTotals {
        val raw = prefs.getString(KEY_TODAY_TOTALS, null) ?: return TodayTotals()
        return runCatching { json.decodeFromString(TodayTotals.serializer(), raw) }
            .getOrDefault(TodayTotals())
    }

    fun writeTodayTotals(totals: TodayTotals) {
        prefs.edit()
            .putString(KEY_TODAY_TOTALS, json.encodeToString(TodayTotals.serializer(), totals))
            .apply()
    }

    // ---------- timers ----------

    fun readTimer(): StoredTimer? {
        val raw = prefs.getString(KEY_TIMER, null) ?: return null
        return runCatching { json.decodeFromString(StoredTimer.serializer(), raw) }.getOrNull()
    }

    fun writeTimer(timer: StoredTimer?) {
        prefs.edit().apply {
            if (timer == null) remove(KEY_TIMER)
            else putString(KEY_TIMER, json.encodeToString(StoredTimer.serializer(), timer))
        }.apply()
    }

    /**
     * The running rest, or null when there is none.
     *
     * [KEY_REST] alone is what an older build wrote, and it still means what it
     * meant then: an open-ended stopwatch. A rest saved by this build adds the
     * two fields a countdown needs beside it, so upgrading mid-rest keeps the
     * rest rather than dropping it.
     */
    fun readRest(): StoredRest? {
        val startedAt = prefs.getLong(KEY_REST, 0L).takeIf { it > 0L } ?: return null
        return StoredRest(
            startedAt = startedAt,
            endAt = prefs.getLong(KEY_REST_END, 0L).takeIf { it > 0L },
            durationMinutes = prefs.getInt(KEY_REST_MINUTES, 0),
            // A rest written by an older build was published on the way in, or
            // was never going to be; treating it as published keeps that
            // build's behaviour rather than re-uploading a finished rest.
            cloudSynced = prefs.getBoolean(KEY_REST_SYNCED, true),
        )
    }

    fun writeRest(rest: StoredRest?) {
        prefs.edit().apply {
            if (rest == null) {
                remove(KEY_REST)
                remove(KEY_REST_END)
                remove(KEY_REST_MINUTES)
                remove(KEY_REST_SYNCED)
            } else {
                putLong(KEY_REST, rest.startedAt)
                if (rest.endAt == null) remove(KEY_REST_END) else putLong(KEY_REST_END, rest.endAt)
                putInt(KEY_REST_MINUTES, rest.durationMinutes)
                putBoolean(KEY_REST_SYNCED, rest.cloudSynced)
            }
        }.apply()
    }

    // ---------- plumbing ----------

    private fun <T> read(key: String, serializer: kotlinx.serialization.KSerializer<T>): List<T> {
        val raw = prefs.getString(key, null) ?: return emptyList()
        // Corrupt JSON should cost the user that one list, not crash the app on
        // every launch from then on.
        return runCatching { json.decodeFromString(ListSerializer(serializer), raw) }
            .getOrElse { emptyList() }
    }

    private fun <T> write(
        key: String,
        serializer: kotlinx.serialization.KSerializer<T>,
        values: List<T>,
    ) {
        prefs.edit().putString(key, json.encodeToString(ListSerializer(serializer), values)).apply()
    }

    private companion object {
        const val PREFS_NAME = "timbertimer-data"
        const val KEY_SESSIONS = "sessions"
        const val KEY_CLOUD_USER = "cloud-user"
        const val KEY_CLOUD_SESSIONS = "cloud-sessions"
        const val KEY_CLOUD_NOTES = "cloud-notes"
        const val KEY_PENDING = "pending-sessions"
        const val KEY_PROJECTS = "projects"
        const val KEY_CLOUD_PROJECTS = "cloud-projects"
        const val KEY_NOTES = "notes"
        const val KEY_NOTES_ORDER = "notes-order"
        const val KEY_WIDGET_NOTES = "widget-notes"
        const val KEY_TODAY_TOTALS = "today-totals"
        const val KEY_TIMER = "timer"
        const val KEY_REST = "rest-started-at"
        const val KEY_REST_END = "rest-end-at"
        const val KEY_REST_MINUTES = "rest-duration-minutes"
        const val KEY_REST_SYNCED = "rest-cloud-synced"
    }
}

/**
 * The running rest as written to disk.
 *
 * [endAt] absent is the open-ended stopwatch, which is also what every rest
 * written before rest countdowns existed was.
 */
data class StoredRest(
    val startedAt: Long,
    val endAt: Long?,
    val durationMinutes: Int,
    val cloudSynced: Boolean,
)

/**
 * One task as the widget needs it: what it says, whether it is done, which
 * list it's on, and — for a Today task — which day. Both the general and the
 * Today widget read from the same snapshot, each filtering to its own list;
 * see [com.example.timbertimer.widget.TodoWidgetService] and
 * [com.example.timbertimer.widget.TodayTodoWidgetService].
 */
@Serializable
data class WidgetNote(
    val id: String,
    val text: String,
    val done: Boolean,
    val list: String = "general",
    val forDate: String? = null,
)

/**
 * One project's share of a day, as the widget draws it.
 *
 * [name] is the stored name, not a translated one: built-in projects are held
 * in English so a record means the same thing on every device, and the label is
 * localized where it is rendered. Freezing a translation into the snapshot
 * would leave it in whatever language it was written in.
 */
@Serializable
data class WidgetProjectTotal(
    val id: String,
    val name: String,
    /** `#rrggbb`, straight from the project. */
    val color: String,
    val minutes: Int,
)

/**
 * A day's totals, as of the last time anything changed.
 *
 * [dateKey] is the local calendar day these belong to, in `yyyy-MM-dd`. A
 * reader compares it against today before believing any of it — see
 * [LocalStore.readTodayTotals].
 */
@Serializable
data class TodayTotals(
    val dateKey: String = "",
    val projects: List<WidgetProjectTotal> = emptyList(),
    /** Completed rests, which is what the stubborn notification counts. */
    val rests: Int = 0,
) {
    /** Total focused minutes, rests included — the widget's header figure. */
    val minutes: Int get() = projects.sumOf { it.minutes }

    /**
     * How long today's rests ran, all together.
     *
     * Rest keeps a project row like any other, so this is simply that row's
     * total — a separate figure from [rests], which counts how many there were.
     * Zero when none have been taken, which is also what a day with no Rest row
     * at all should read as.
     */
    val restMinutes: Int
        get() = projects.firstOrNull { it.id == Projects.REST_ID }?.minutes ?: 0

    /** Empty rather than stale: the totals only speak for the day they name. */
    fun forDay(todayKey: String): TodayTotals =
        if (dateKey == todayKey) this else TodayTotals(dateKey = todayKey)
}

/**
 * The running timer as written to disk.
 *
 * [endAt] is an absolute instant, so restoring this after a reboot needs no
 * correction — the timer is simply due, or it is not.
 */
@Serializable
data class StoredTimer(
    val id: String,
    val mode: String,
    val title: String,
    /** Absent in timers written by an older build; those fall back to Focus. */
    val projectId: String? = null,
    val durationMinutes: Int,
    val durationSeconds: Int,
    val startedAt: Long,
    val endAt: Long,
    val cloudSynced: Boolean = false,
)
