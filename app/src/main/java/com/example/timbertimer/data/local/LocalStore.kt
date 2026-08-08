package com.example.timbertimer.data.local

import android.content.Context
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

    /** Epoch millis the rest stopwatch started, or null when it is not running. */
    fun readRestStartedAt(): Long? = prefs.getLong(KEY_REST, 0L).takeIf { it > 0L }

    fun writeRestStartedAt(startedAt: Long?) {
        prefs.edit().apply {
            if (startedAt == null) remove(KEY_REST) else putLong(KEY_REST, startedAt)
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
        const val KEY_TIMER = "timer"
        const val KEY_REST = "rest-started-at"
    }
}

/** One task as the widget needs it: what it says, and whether it is done. */
@Serializable
data class WidgetNote(
    val id: String,
    val text: String,
    val done: Boolean,
)

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
