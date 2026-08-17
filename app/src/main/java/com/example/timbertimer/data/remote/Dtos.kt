package com.example.timbertimer.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Read and write shapes are kept apart on purpose.
 *
 * Read rows are forgiving — every optional column has a default, so a row
 * written by an older or newer client still decodes. Write payloads have no
 * defaults at all, because a serializer that silently omits a field would turn
 * an upsert into a partial update: unchecking a to-do would drop `done` from the
 * body and PostgREST would leave the old value in place.
 */

// ---------- focus_sessions ----------

@Serializable
data class FocusSessionRow(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val title: String = "",
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("actual_minutes") val actualMinutes: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("ended_at") val endedAt: String? = null,
    @SerialName("tree_kind") val treeKind: String = "young sprout",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    /**
     * Read-only, and never written back.
     *
     * The column is gone from the database, but rows saved on this device by an
     * older build still carry it — and for a row written before projects
     * existed it is the only thing that tells a rest apart from an abandoned
     * session. [com.example.timbertimer.data.model.Projects.resolveId] is its
     * one reader.
     */
    @SerialName("status") val legacyStatus: String? = null,
)

@Serializable
data class FocusSessionInsert(
    val id: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("actual_minutes") val actualMinutes: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("tree_kind") val treeKind: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/**
 * The same insert for a database that predates the projects migration.
 *
 * PostgREST rejects the whole request when a body names a column that does not
 * exist, so a record saved against an un-migrated project has to leave the
 * field out entirely rather than send null. It still saves, and picks its
 * project up from its title exactly as it did before.
 */
@Serializable
data class FocusSessionInsertLegacy(
    val id: String,
    @SerialName("user_id") val userId: String,
    val title: String,
    @SerialName("actual_minutes") val actualMinutes: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("tree_kind") val treeKind: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** No `id`, `user_id` or `created_at`: those are never rewritten by an edit. */
@Serializable
data class FocusSessionUpdate(
    val title: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("actual_minutes") val actualMinutes: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("tree_kind") val treeKind: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class FocusSessionUpdateLegacy(
    val title: String,
    @SerialName("actual_minutes") val actualMinutes: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("ended_at") val endedAt: String,
    @SerialName("tree_kind") val treeKind: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Moving a deleted project's records onto another one, in a single request. */
@Serializable
data class SessionProjectUpdate(
    @SerialName("project_id") val projectId: String,
    @SerialName("updated_at") val updatedAt: String,
)

// ---------- projects ----------

@Serializable
data class ProjectRow(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val name: String = "",
    val color: String = "",
    val tree: String = "pine",
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ProjectUpsert(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val color: String,
    val tree: String,
    @SerialName("sort_order") val sortOrder: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

// ---------- active_focus_timers ----------

@Serializable
data class ActiveTimerRow(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("timer_id") val timerId: String? = null,
    val mode: String = "countdown",
    val title: String = "",
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
    @SerialName("duration_seconds") val durationSeconds: Int = 0,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("end_at") val endAt: String? = null,
)

@Serializable
data class ActiveTimerUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("timer_id") val timerId: String,
    val mode: String,
    val title: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Same row without `project_id`, for a database that predates that column. */
@Serializable
data class ActiveTimerUpsertLegacy(
    @SerialName("user_id") val userId: String,
    @SerialName("timer_id") val timerId: String,
    val mode: String,
    val title: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("started_at") val startedAt: String,
    @SerialName("end_at") val endAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

/** Just enough of the delete response to count what was actually removed. */
@Serializable
data class DeletedUserRow(@SerialName("user_id") val userId: String? = null)

// ---------- active_rest_timers ----------

@Serializable
data class RestTimerRow(
    @SerialName("started_at") val startedAt: String? = null,
    /** Absent on a database that predates rest countdowns; then it is a stopwatch. */
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
)

@Serializable
data class RestTimerUpsert(
    @SerialName("user_id") val userId: String,
    @SerialName("started_at") val startedAt: String,
    /** Null is a running stopwatch, which is a value the column accepts. */
    @SerialName("end_at") val endAt: String? = null,
    @SerialName("duration_minutes") val durationMinutes: Int = 0,
    @SerialName("updated_at") val updatedAt: String,
) {
    /**
     * The same row as a database predating the rest-countdown migration will
     * accept. PostgREST refuses a whole request that names a column which does
     * not exist, so the two fields have to be dropped rather than sent as null.
     */
    fun withoutCountdown() = RestTimerUpsertLegacy(
        userId = userId,
        startedAt = startedAt,
        updatedAt = updatedAt,
    )
}

@Serializable
data class RestTimerUpsertLegacy(
    @SerialName("user_id") val userId: String,
    @SerialName("started_at") val startedAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

// ---------- notes ----------

@Serializable
data class NoteRow(
    val id: String,
    @SerialName("user_id") val userId: String? = null,
    val text: String = "",
    val done: Boolean = false,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class NoteUpsert(
    val id: String,
    @SerialName("user_id") val userId: String,
    val text: String,
    val done: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("sort_order") val sortOrder: Int,
)

/** Same row without `sort_order`, for projects whose SQL predates that column. */
@Serializable
data class NoteUpsertLegacy(
    val id: String,
    @SerialName("user_id") val userId: String,
    val text: String,
    val done: Boolean,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class NoteDoneUpdate(
    val done: Boolean,
    @SerialName("updated_at") val updatedAt: String,
)

// ---------- auth ----------

/** Token payload returned by GoTrue for every grant type. */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long = 3600,
    @SerialName("token_type") val tokenType: String = "bearer",
    val user: UserPayload? = null,
)

@Serializable
data class UserPayload(
    val id: String,
    val email: String? = null,
    @SerialName("user_metadata") val metadata: UserMetadata? = null,
)

@Serializable
data class UserMetadata(
    @SerialName("full_name") val fullName: String? = null,
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

/** Error body GoTrue and PostgREST return on failure. */
@Serializable
data class ApiError(
    val message: String? = null,
    val msg: String? = null,
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val hint: String? = null,
    val code: String? = null,
) {
    fun bestMessage(): String? =
        listOfNotNull(message, msg, errorDescription, error, hint).firstOrNull { it.isNotBlank() }
}
