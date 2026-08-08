package com.example.timbertimer.data

import com.example.timbertimer.core.Time
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.data.remote.FocusSessionInsert
import com.example.timbertimer.data.remote.FocusSessionRow
import com.example.timbertimer.data.remote.FocusSessionUpdate
import com.example.timbertimer.data.remote.ProjectRow
import com.example.timbertimer.data.remote.ProjectUpsert

/**
 * Converts between database rows and [FocusRecord], applying the same
 * corrections the web client applies.
 *
 * Two reasons this matters beyond tidiness. First, the table's CHECK
 * constraints are strict — a title outside 1..80 characters or minutes outside
 * their range is rejected outright, so clamping here is what stops a long
 * session name from failing the save. Second, a record has to describe the same
 * tree on both clients, so the species fallbacks are reproduced exactly.
 */
object RecordMapper {

    /** The web app's `cleanMinutes`: out-of-range values clamp, missing ones fall back. */
    fun cleanMinutes(value: Int?, fallback: Int, min: Int): Int =
        value?.coerceIn(min, Limits.MINUTES_MAX) ?: fallback

    fun cleanTitle(value: String?): String =
        value?.trim()?.take(Limits.TITLE_MAX)?.ifBlank { null } ?: Limits.DEFAULT_TITLE

    fun normalize(row: FocusSessionRow): FocusRecord {
        val now = System.currentTimeMillis()
        val duration = cleanMinutes(row.durationMinutes, Limits.DEFAULT_DURATION, 1)
        val actual = cleanMinutes(row.actualMinutes, duration, 0)
        val title = cleanTitle(row.title)
        val status = RecordStatus.from(row.status)
        val startedAt = Time.parseIso(row.startedAt) ?: now
        val treeKind = resolveTreeKind(row.treeKind, status)
        return FocusRecord(
            id = row.id,
            title = title,
            projectId = Projects.resolveId(row.projectId, status, treeKind, title),
            durationMinutes = duration,
            actualMinutes = actual,
            status = status,
            startedAt = startedAt,
            endedAt = Time.parseIso(row.endedAt) ?: startedAt,
            treeKind = treeKind,
            createdAt = Time.parseIso(row.createdAt) ?: now,
            updatedAt = Time.parseIso(row.updatedAt) ?: now,
        )
    }

    /**
     * Keeps the species that was actually stored on this record.
     *
     * A rest plants a wilted tree even though it completes, and an abandoned
     * session wilts whatever it once was. Anything unrecognised — a row from an
     * old schema — falls back to pine, and is redrawn from its project anyway.
     */
    fun resolveTreeKind(stored: String?, status: RecordStatus): String {
        if (status == RecordStatus.ABANDONED) return TreeSpecies.WILTED.label
        if (stored == TreeSpecies.WILTED.label) return stored
        return TreeSpecies.byLabel(stored)?.label ?: TreeSpecies.PINE.label
    }

    /**
     * The species to plant: the project's, unless the session was abandoned, in
     * which case it wilts whatever the project grows.
     */
    fun pickTreeKind(project: Project, status: RecordStatus): String =
        if (status == RecordStatus.ABANDONED) TreeSpecies.WILTED.label
        else project.species.label

    fun pickTreeKind(book: ProjectBook, projectId: String, status: RecordStatus): String =
        pickTreeKind(book[projectId], status)

    // ---------- to the database ----------

    fun toRow(record: FocusRecord, userId: String?): FocusSessionRow = FocusSessionRow(
        id = record.id,
        userId = userId,
        title = cleanTitle(record.title),
        projectId = record.projectId,
        durationMinutes = cleanMinutes(record.durationMinutes, Limits.DEFAULT_DURATION, 1),
        actualMinutes = cleanMinutes(record.actualMinutes, record.durationMinutes, 0),
        status = record.status.wire,
        startedAt = Time.toIso(record.startedAt),
        endedAt = Time.toIso(record.endedAt),
        treeKind = record.treeKind,
        createdAt = Time.toIso(record.createdAt),
        updatedAt = Time.toIso(record.updatedAt),
    )

    fun toInsert(record: FocusRecord, userId: String): FocusSessionInsert = FocusSessionInsert(
        id = record.id,
        userId = userId,
        title = cleanTitle(record.title),
        projectId = record.projectId,
        durationMinutes = cleanMinutes(record.durationMinutes, Limits.DEFAULT_DURATION, 1),
        actualMinutes = cleanMinutes(record.actualMinutes, record.durationMinutes, 0),
        status = record.status.wire,
        startedAt = Time.toIso(record.startedAt),
        endedAt = Time.toIso(record.endedAt),
        treeKind = record.treeKind,
        createdAt = Time.toIso(record.createdAt),
        updatedAt = Time.toIso(record.updatedAt),
    )

    fun toInsert(row: FocusSessionRow, userId: String): FocusSessionInsert {
        val fallbackNow = Time.toIso(System.currentTimeMillis())
        val status = RecordStatus.from(row.status)
        val title = cleanTitle(row.title)
        return FocusSessionInsert(
            id = row.id,
            userId = userId,
            title = title,
            projectId = Projects.resolveId(row.projectId, status, row.treeKind, title),
            durationMinutes = cleanMinutes(row.durationMinutes, Limits.DEFAULT_DURATION, 1),
            actualMinutes = cleanMinutes(row.actualMinutes, row.durationMinutes, 0),
            status = row.status,
            startedAt = row.startedAt ?: fallbackNow,
            endedAt = row.endedAt ?: row.startedAt ?: fallbackNow,
            treeKind = row.treeKind,
            createdAt = row.createdAt ?: fallbackNow,
            updatedAt = row.updatedAt ?: fallbackNow,
        )
    }

    fun toUpdate(record: FocusRecord): FocusSessionUpdate = FocusSessionUpdate(
        title = cleanTitle(record.title),
        projectId = record.projectId,
        durationMinutes = cleanMinutes(record.durationMinutes, Limits.DEFAULT_DURATION, 1),
        actualMinutes = cleanMinutes(record.actualMinutes, record.durationMinutes, 0),
        status = record.status.wire,
        startedAt = Time.toIso(record.startedAt),
        endedAt = Time.toIso(record.endedAt),
        treeKind = record.treeKind,
        updatedAt = Time.toIso(record.updatedAt),
    )

    // ---------- projects ----------

    fun toProject(row: ProjectRow): Project {
        val now = System.currentTimeMillis()
        return Projects.normalize(
            id = row.id,
            name = row.name,
            color = row.color,
            tree = row.tree,
            sortOrder = row.sortOrder,
            createdAt = Time.parseIso(row.createdAt) ?: now,
            updatedAt = Time.parseIso(row.updatedAt) ?: now,
        )
    }

    fun toProjectRow(project: Project): ProjectRow = ProjectRow(
        id = project.id,
        name = project.name,
        color = project.color,
        tree = project.tree,
        sortOrder = project.sortOrder,
        createdAt = Time.toIso(project.createdAt),
        updatedAt = Time.toIso(project.updatedAt),
    )

    fun toProjectUpsert(project: Project, userId: String): ProjectUpsert = ProjectUpsert(
        id = project.id,
        userId = userId,
        name = project.name.take(Projects.NAME_MAX),
        color = project.color,
        tree = project.tree,
        sortOrder = project.sortOrder,
        createdAt = Time.toIso(project.createdAt),
        updatedAt = Time.toIso(System.currentTimeMillis()),
    )
}
