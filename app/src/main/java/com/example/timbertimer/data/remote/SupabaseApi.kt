package com.example.timbertimer.data.remote

import com.example.timbertimer.data.model.Projects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Talks to Supabase's PostgREST endpoints for the four tables the web app uses.
 *
 * Every call takes the caller's access token and user id explicitly. Row level
 * security already restricts rows to the signed-in user, but the `user_id`
 * filters are still sent so a request can never accidentally page through more
 * than it means to, and so writes fail loudly rather than silently landing on
 * another user's row.
 */
class SupabaseApi(
    private val client: OkHttpClient,
    private val json: Json,
) {

    // ---------- focus_sessions ----------

    suspend fun fetchSessions(token: String, userId: String): List<FocusSessionRow> {
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE)
            .addQueryParameter("select", "*")
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("order", "started_at.desc")
            .build()
        return getList(url, token, FocusSessionRow.serializer())
    }

    /**
     * Returns the row Postgres actually stored, defaults and triggers included.
     *
     * [withProject] is false once the caller has learned that this database
     * predates the projects migration, in which case `project_id` is left out of
     * the body entirely — PostgREST rejects the whole request for naming a
     * column that does not exist.
     */
    suspend fun insertSession(
        token: String,
        row: FocusSessionInsert,
        withProject: Boolean,
    ): FocusSessionRow {
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE).build()
        val payload = if (withProject) {
            json.encodeToString(ListSerializer(FocusSessionInsert.serializer()), listOf(row))
        } else {
            json.encodeToString(
                ListSerializer(FocusSessionInsertLegacy.serializer()),
                listOf(row.withoutProject()),
            )
        }
        val body = executeForBody(
            authorized(url, token)
                .addHeader("Content-Type", JSON)
                .addHeader("Prefer", "return=representation")
                .post(payload.toRequestBody(JSON.toMediaType()))
                .build()
        )
        return json.decodeFromString(ListSerializer(FocusSessionRow.serializer()), body).first()
    }

    suspend fun updateSession(
        token: String,
        userId: String,
        id: String,
        update: FocusSessionUpdate,
        withProject: Boolean,
    ): FocusSessionRow {
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("id", "eq.$id")
            .build()
        val payload = if (withProject) {
            json.encodeToString(FocusSessionUpdate.serializer(), update)
        } else {
            json.encodeToString(FocusSessionUpdateLegacy.serializer(), update.withoutProject())
        }
        val body = executeForBody(
            authorized(url, token)
                .addHeader("Content-Type", JSON)
                .addHeader("Prefer", "return=representation")
                .patch(payload.toRequestBody(JSON.toMediaType()))
                .build()
        )
        val rows = json.decodeFromString(ListSerializer(FocusSessionRow.serializer()), body)
        return rows.firstOrNull()
            ?: throw SupabaseException("That record no longer exists.", rejected = true)
    }

    /**
     * Repoints a set of records at another project, which is what deleting one
     * does to its records rather than orphaning them.
     */
    suspend fun moveSessionsToProject(
        token: String,
        userId: String,
        ids: List<String>,
        update: SessionProjectUpdate,
    ) {
        if (ids.isEmpty()) return
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("id", "in.(${ids.joinToString(",")})")
            .build()
        val payload = json.encodeToString(SessionProjectUpdate.serializer(), update)
        execute(
            authorized(url, token)
                .addHeader("Content-Type", JSON)
                .addHeader("Prefer", "return=minimal")
                .patch(payload.toRequestBody(JSON.toMediaType()))
                .build()
        )
    }

    /**
     * Uploads records that were held back while the network was gone.
     *
     * Upsert rather than insert because a retry can follow a save that actually
     * reached Postgres but whose response never made it back. Conflicting on the
     * id makes replaying the outbox harmless however many times it happens.
     */
    suspend fun upsertSessions(token: String, rows: List<FocusSessionInsert>, withProject: Boolean) {
        if (rows.isEmpty()) return
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE)
            .addQueryParameter("on_conflict", "id")
            .build()
        val payload = if (withProject) {
            json.encodeToString(ListSerializer(FocusSessionInsert.serializer()), rows)
        } else {
            json.encodeToString(
                ListSerializer(FocusSessionInsertLegacy.serializer()),
                rows.map { it.withoutProject() },
            )
        }
        execute(
            authorized(url, token)
                .addHeader("Content-Type", JSON)
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(payload.toRequestBody(JSON.toMediaType()))
                .build()
        )
    }

    suspend fun deleteSession(token: String, userId: String, id: String) {
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("id", "eq.$id")
            .build()
        execute(authorized(url, token).delete().build())
    }

    suspend fun deleteAllSessions(token: String, userId: String) {
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .build()
        execute(authorized(url, token).delete().build())
    }

    // ---------- active_focus_timers ----------

    suspend fun fetchActiveTimer(token: String, userId: String): ActiveTimerRow? {
        val url = restUrl(SupabaseConfig.ACTIVE_TIMERS_TABLE)
            .addQueryParameter("select", "*")
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("limit", "1")
            .build()
        return getList(url, token, ActiveTimerRow.serializer()).firstOrNull()
    }

    suspend fun upsertActiveTimer(token: String, row: ActiveTimerUpsert, withProject: Boolean) {
        val url = restUrl(SupabaseConfig.ACTIVE_TIMERS_TABLE)
            .addQueryParameter("on_conflict", "user_id")
            .build()
        val payload = if (withProject) {
            json.encodeToString(ListSerializer(ActiveTimerUpsert.serializer()), listOf(row))
        } else {
            json.encodeToString(
                ListSerializer(ActiveTimerUpsertLegacy.serializer()),
                listOf(row.withoutProject()),
            )
        }
        execute(
            authorized(url, token)
                .addHeader("Content-Type", JSON)
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(payload.toRequestBody(JSON.toMediaType()))
                .build()
        )
    }

    /**
     * Deletes the shared active-timer row only if it is still *this* timer, and
     * reports whether it was the one to remove it.
     *
     * This is how two signed-in devices avoid both writing a record for the same
     * session: the delete is the claim, and Postgres arbitrates. A device that
     * finds nothing to delete knows another one already finished the session.
     */
    suspend fun claimActiveTimer(token: String, userId: String, timerId: String): Boolean {
        val url = restUrl(SupabaseConfig.ACTIVE_TIMERS_TABLE)
            .addQueryParameter("select", "user_id")
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("timer_id", "eq.$timerId")
            .build()
        val body = executeForBody(
            authorized(url, token)
                .addHeader("Prefer", "return=representation")
                .delete()
                .build()
        )
        return json.decodeFromString(ListSerializer(DeletedUserRow.serializer()), body).isNotEmpty()
    }

    suspend fun deleteActiveTimer(token: String, userId: String) {
        val url = restUrl(SupabaseConfig.ACTIVE_TIMERS_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .build()
        execute(authorized(url, token).delete().build())
    }

    // ---------- active_rest_timers ----------

    /**
     * [withCountdown] names the two columns a rest countdown needs. Naming a
     * column that does not exist fails the whole select, so a database that
     * predates the migration is read with the original projection and every
     * rest on it reads as the open-ended stopwatch it was written as.
     */
    suspend fun fetchRestTimer(
        token: String,
        userId: String,
        withCountdown: Boolean,
    ): RestTimerRow? {
        val url = restUrl(SupabaseConfig.ACTIVE_RESTS_TABLE)
            .addQueryParameter(
                "select",
                if (withCountdown) "started_at,end_at,duration_minutes" else "started_at",
            )
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("limit", "1")
            .build()
        return getList(url, token, RestTimerRow.serializer()).firstOrNull()
    }

    suspend fun upsertRestTimer(token: String, row: RestTimerUpsert, withCountdown: Boolean) {
        val url = restUrl(SupabaseConfig.ACTIVE_RESTS_TABLE)
            .addQueryParameter("on_conflict", "user_id")
            .build()
        val payload = if (withCountdown) {
            json.encodeToString(ListSerializer(RestTimerUpsert.serializer()), listOf(row))
        } else {
            json.encodeToString(
                ListSerializer(RestTimerUpsertLegacy.serializer()),
                listOf(row.withoutCountdown()),
            )
        }
        execute(
            authorized(url, token)
                .addHeader("Content-Type", JSON)
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(payload.toRequestBody(JSON.toMediaType()))
                .build()
        )
    }

    suspend fun deleteRestTimer(token: String, userId: String) {
        val url = restUrl(SupabaseConfig.ACTIVE_RESTS_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .build()
        execute(authorized(url, token).delete().build())
    }

    // ---------- notes ----------

    /**
     * Fetches the to-do list in its saved order.
     *
     * `sort_order` is a later addition to the schema, so a project that has not
     * run the migration falls back to newest-first. [NotesPage.orderedRemotely]
     * tells the caller which happened, because in the fallback case this
     * device's own saved order is the better answer.
     */
    suspend fun fetchNotes(token: String, userId: String): NotesPage {
        val ordered = restUrl(SupabaseConfig.NOTES_TABLE)
            .addQueryParameter("select", "*")
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("order", "sort_order.asc,created_at.desc")
            .build()
        runCatching { getList(ordered, token, NoteRow.serializer()) }
            .onSuccess { return NotesPage(it, orderedRemotely = true) }

        val fallback = restUrl(SupabaseConfig.NOTES_TABLE)
            .addQueryParameter("select", "*")
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("order", "created_at.desc")
            .build()
        return NotesPage(getList(fallback, token, NoteRow.serializer()), orderedRemotely = false)
    }

    /**
     * Writes the whole list back with each item's position, so every signed-in
     * device shows the same order. Upsert also inserts notes that are not saved
     * yet. Returns false when the project still lacks `sort_order`, in which
     * case the notes were saved but their order was not.
     */
    suspend fun upsertNotes(token: String, rows: List<NoteUpsert>): Boolean {
        if (rows.isEmpty()) return true
        val url = restUrl(SupabaseConfig.NOTES_TABLE)
            .addQueryParameter("on_conflict", "id")
            .build()

        val full = json.encodeToString(ListSerializer(NoteUpsert.serializer()), rows)
        if (runCatching { postUpsert(url, token, full) }.isSuccess) return true

        // Most likely `list`/`for_date` (the today/general split) are missing;
        // retry with just what an older, sort_order-only database still has.
        val withOrderRows = rows.map { it.withoutTodayList() }
        val withOrder = json.encodeToString(ListSerializer(NoteUpsertWithOrder.serializer()), withOrderRows)
        if (runCatching { postUpsert(url, token, withOrder) }.isSuccess) return false

        // sort_order is missing too — the oldest shape the table can be in.
        val legacyRows = withOrderRows.map { it.withoutOrder() }
        val legacy = json.encodeToString(ListSerializer(NoteUpsertLegacy.serializer()), legacyRows)
        postUpsert(url, token, legacy)
        return false
    }

    suspend fun updateNoteDone(token: String, userId: String, id: String, update: NoteDoneUpdate) {
        val url = restUrl(SupabaseConfig.NOTES_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("id", "eq.$id")
            .build()
        val payload = json.encodeToString(NoteDoneUpdate.serializer(), update)
        execute(
            authorized(url, token)
                .addHeader("Content-Type", JSON)
                .addHeader("Prefer", "return=minimal")
                .patch(payload.toRequestBody(JSON.toMediaType()))
                .build()
        )
    }

    suspend fun deleteNote(token: String, userId: String, id: String) {
        val url = restUrl(SupabaseConfig.NOTES_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("id", "eq.$id")
            .build()
        execute(authorized(url, token).delete().build())
    }

    // ---------- projects ----------

    /**
     * The projects table only exists once the updated SQL has been run, so every
     * caller has to be ready for this to fail and fall back to the device's own
     * copy rather than treating it as a lost account.
     */
    suspend fun fetchProjects(token: String, userId: String): List<ProjectRow> {
        val url = restUrl(SupabaseConfig.PROJECTS_TABLE)
            .addQueryParameter("select", "*")
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("order", "sort_order.asc")
            .build()
        return getList(url, token, ProjectRow.serializer())
    }

    /** The primary key is (user_id, id), so that is what a conflict resolves on. */
    suspend fun upsertProjects(token: String, rows: List<ProjectUpsert>) {
        if (rows.isEmpty()) return
        val url = restUrl(SupabaseConfig.PROJECTS_TABLE)
            .addQueryParameter("on_conflict", "user_id,id")
            .build()
        postUpsert(url, token, json.encodeToString(ListSerializer(ProjectUpsert.serializer()), rows))
    }

    suspend fun deleteProject(token: String, userId: String, id: String) {
        val url = restUrl(SupabaseConfig.PROJECTS_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("id", "eq.$id")
            .build()
        execute(authorized(url, token).delete().build())
    }

    /**
     * Every project this user made, in one request.
     *
     * The two built-ins are excluded rather than deleted and re-created. They
     * are what a rest is filed under and what a session with nothing chosen
     * falls back to, so a window where they do not exist is a window where
     * another device can write a record pointing at nothing.
     */
    suspend fun deleteUserProjects(token: String, userId: String) {
        val kept = Projects.BUILTIN_IDS.joinToString(",")
        val url = restUrl(SupabaseConfig.PROJECTS_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("id", "not.in.($kept)")
            .build()
        execute(authorized(url, token).delete().build())
    }

    // ---------- plumbing ----------

    private suspend fun postUpsert(url: HttpUrl, token: String, payload: String) {
        execute(
            authorized(url, token)
                .addHeader("Content-Type", JSON)
                .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(payload.toRequestBody(JSON.toMediaType()))
                .build()
        )
    }

    private fun restUrl(table: String): HttpUrl.Builder =
        "${SupabaseConfig.REST_PATH}/$table".toHttpUrl().newBuilder()

    private fun authorized(url: HttpUrl, token: String): Request.Builder =
        Request.Builder()
            .url(url)
            .addHeader("apikey", SupabaseConfig.ANON_KEY)
            .addHeader("Authorization", "Bearer $token")

    private suspend fun <T> getList(
        url: HttpUrl,
        token: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> {
        val body = executeForBody(authorized(url, token).get().build())
        return json.decodeFromString(ListSerializer(serializer), body)
    }

    private suspend fun executeForBody(request: Request): String =
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw failureFor(response, body)
                body
            }
        }

    private suspend fun execute(request: Request) {
        withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw failureFor(response)
            }
        }
    }

    private fun failureFor(response: Response, preRead: String? = null): SupabaseException {
        val body = preRead ?: runCatching { response.body?.string() }.getOrNull().orEmpty()
        val error = runCatching { json.decodeFromString(ApiError.serializer(), body) }.getOrNull()
        return SupabaseException(
            error?.bestMessage() ?: "Supabase request failed (HTTP ${response.code}).",
            // Anything the server answered at all is a rejection; only transport
            // failures throw IOException before reaching here.
            rejected = true,
            // Carried through so the caller can tell a missing column from a
            // refusal it should simply retry.
            code = error?.code,
        )
    }

    private companion object {
        const val JSON = "application/json"
    }
}

/** Notes as stored remotely, plus whether the server could order them. */
data class NotesPage(val rows: List<NoteRow>, val orderedRemotely: Boolean)

// The same payloads with `project_id` dropped, for a database that has not had
// the projects migration run against it yet.

private fun FocusSessionInsert.withoutProject() = FocusSessionInsertLegacy(
    id = id,
    userId = userId,
    title = title,
    actualMinutes = actualMinutes,
    startedAt = startedAt,
    endedAt = endedAt,
    treeKind = treeKind,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private fun FocusSessionUpdate.withoutProject() = FocusSessionUpdateLegacy(
    title = title,
    actualMinutes = actualMinutes,
    startedAt = startedAt,
    endedAt = endedAt,
    treeKind = treeKind,
    updatedAt = updatedAt,
)

private fun ActiveTimerUpsert.withoutProject() = ActiveTimerUpsertLegacy(
    userId = userId,
    timerId = timerId,
    mode = mode,
    title = title,
    durationMinutes = durationMinutes,
    durationSeconds = durationSeconds,
    startedAt = startedAt,
    endAt = endAt,
    updatedAt = updatedAt,
)
