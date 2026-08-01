package com.example.timbertimer.data.remote

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

    /** Returns the row Postgres actually stored, defaults and triggers included. */
    suspend fun insertSession(token: String, row: FocusSessionInsert): FocusSessionRow {
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE).build()
        val payload = json.encodeToString(ListSerializer(FocusSessionInsert.serializer()), listOf(row))
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
    ): FocusSessionRow {
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE)
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("id", "eq.$id")
            .build()
        val payload = json.encodeToString(FocusSessionUpdate.serializer(), update)
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
     * Uploads records that were held back while the network was gone.
     *
     * Upsert rather than insert because a retry can follow a save that actually
     * reached Postgres but whose response never made it back. Conflicting on the
     * id makes replaying the outbox harmless however many times it happens.
     */
    suspend fun upsertSessions(token: String, rows: List<FocusSessionInsert>) {
        if (rows.isEmpty()) return
        val url = restUrl(SupabaseConfig.SESSIONS_TABLE)
            .addQueryParameter("on_conflict", "id")
            .build()
        val payload = json.encodeToString(ListSerializer(FocusSessionInsert.serializer()), rows)
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

    suspend fun upsertActiveTimer(token: String, row: ActiveTimerUpsert) {
        val url = restUrl(SupabaseConfig.ACTIVE_TIMERS_TABLE)
            .addQueryParameter("on_conflict", "user_id")
            .build()
        val payload = json.encodeToString(ListSerializer(ActiveTimerUpsert.serializer()), listOf(row))
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

    suspend fun fetchRestTimer(token: String, userId: String): RestTimerRow? {
        val url = restUrl(SupabaseConfig.ACTIVE_RESTS_TABLE)
            .addQueryParameter("select", "started_at")
            .addQueryParameter("user_id", "eq.$userId")
            .addQueryParameter("limit", "1")
            .build()
        return getList(url, token, RestTimerRow.serializer()).firstOrNull()
    }

    suspend fun upsertRestTimer(token: String, row: RestTimerUpsert) {
        val url = restUrl(SupabaseConfig.ACTIVE_RESTS_TABLE)
            .addQueryParameter("on_conflict", "user_id")
            .build()
        val payload = json.encodeToString(ListSerializer(RestTimerUpsert.serializer()), listOf(row))
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

        val withOrder = json.encodeToString(ListSerializer(NoteUpsert.serializer()), rows)
        val attempt = runCatching { postUpsert(url, token, withOrder) }
        if (attempt.isSuccess) return true

        val legacyRows = rows.map {
            NoteUpsertLegacy(it.id, it.userId, it.text, it.done, it.createdAt, it.updatedAt)
        }
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
        val parsed = runCatching {
            json.decodeFromString(ApiError.serializer(), body).bestMessage()
        }.getOrNull()
        return SupabaseException(
            parsed ?: "Supabase request failed (HTTP ${response.code}).",
            // Anything the server answered at all is a rejection; only transport
            // failures throw IOException before reaching here.
            rejected = true,
        )
    }

    private companion object {
        const val JSON = "application/json"
    }
}

/** Notes as stored remotely, plus whether the server could order them. */
data class NotesPage(val rows: List<NoteRow>, val orderedRemotely: Boolean)
