package com.example.timbertimer.data

import com.example.timbertimer.core.Seed
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.data.remote.FocusSessionInsert
import com.example.timbertimer.data.remote.FocusSessionRow
import com.example.timbertimer.data.remote.FocusSessionUpdate

/**
 * Converts between database rows and [FocusRecord], applying the same
 * corrections the web client applies.
 *
 * Two reasons this matters beyond tidiness. First, the table's CHECK
 * constraints are strict — a title outside 1..80 characters or minutes outside
 * their range is rejected outright, so clamping here is what stops a long
 * session name from failing the save. Second, a record has to describe the same
 * tree on both clients, so the species fallbacks are reproduced exactly.
 *
 * [treePreference] is passed in rather than read from storage so the whole file
 * stays testable off-device.
 */
object RecordMapper {

    /** The web app's `cleanMinutes`: out-of-range values clamp, missing ones fall back. */
    fun cleanMinutes(value: Int?, fallback: Int, min: Int): Int =
        value?.coerceIn(min, Limits.MINUTES_MAX) ?: fallback

    fun cleanTitle(value: String?): String =
        value?.trim()?.take(Limits.TITLE_MAX)?.ifBlank { null } ?: Limits.DEFAULT_TITLE

    fun normalize(row: FocusSessionRow, treePreference: (String) -> String?): FocusRecord {
        val now = System.currentTimeMillis()
        val duration = cleanMinutes(row.durationMinutes, Limits.DEFAULT_DURATION, 1)
        val actual = cleanMinutes(row.actualMinutes, duration, 0)
        val title = cleanTitle(row.title)
        val status = RecordStatus.from(row.status)
        val startedAt = Time.parseIso(row.startedAt) ?: now
        return FocusRecord(
            id = row.id,
            title = title,
            durationMinutes = duration,
            actualMinutes = actual,
            status = status,
            startedAt = startedAt,
            endedAt = Time.parseIso(row.endedAt) ?: startedAt,
            treeKind = resolveTreeKind(row.treeKind, title, status, treePreference),
            createdAt = Time.parseIso(row.createdAt) ?: now,
            updatedAt = Time.parseIso(row.updatedAt) ?: now,
        )
    }

    /**
     * Keeps the species that was actually chosen for this record. Only a legacy
     * row that never stored a usable `tree_kind` falls back to a derived one.
     */
    fun resolveTreeKind(
        stored: String?,
        title: String,
        status: RecordStatus,
        treePreference: (String) -> String?,
    ): String {
        if (status == RecordStatus.ABANDONED) return TreeSpecies.WILTED.label
        // A rest plants a wilted tree even though it completes — keep it rather
        // than re-deriving a healthy species for it.
        if (stored == TreeSpecies.WILTED.label) return stored
        TreeSpecies.byLabel(stored)?.let { return it.label }
        return speciesForSession(title, treePreference).label
    }

    /**
     * The species to plant, given an explicit pick if there was one.
     * An abandoned session wilts regardless of what was chosen.
     */
    fun pickTreeKind(
        title: String,
        status: RecordStatus,
        chosenSpeciesId: String?,
        treePreference: (String) -> String?,
    ): String {
        if (status == RecordStatus.ABANDONED) return TreeSpecies.WILTED.label
        TreeSpecies.byId(chosenSpeciesId)?.takeIf { it != TreeSpecies.WILTED }?.let { return it.label }
        return speciesForSession(title, treePreference).label
    }

    /** Saved preference for this name, else the stable per-name default. */
    fun speciesForSession(title: String, treePreference: (String) -> String?): TreeSpecies =
        TreeSpecies.byId(treePreference(title))?.takeIf { it != TreeSpecies.WILTED }
            ?: Seed.defaultSpeciesFor(title)

    // ---------- to the database ----------

    fun toRow(record: FocusRecord, userId: String?): FocusSessionRow = FocusSessionRow(
        id = record.id,
        userId = userId,
        title = cleanTitle(record.title),
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
        durationMinutes = cleanMinutes(record.durationMinutes, Limits.DEFAULT_DURATION, 1),
        actualMinutes = cleanMinutes(record.actualMinutes, record.durationMinutes, 0),
        status = record.status.wire,
        startedAt = Time.toIso(record.startedAt),
        endedAt = Time.toIso(record.endedAt),
        treeKind = record.treeKind,
        createdAt = Time.toIso(record.createdAt),
        updatedAt = Time.toIso(record.updatedAt),
    )

    fun toInsert(row: FocusSessionRow, userId: String): FocusSessionInsert = FocusSessionInsert(
        id = row.id,
        userId = userId,
        title = cleanTitle(row.title),
        durationMinutes = cleanMinutes(row.durationMinutes, Limits.DEFAULT_DURATION, 1),
        actualMinutes = cleanMinutes(row.actualMinutes, row.durationMinutes, 0),
        status = row.status,
        startedAt = row.startedAt ?: Time.toIso(System.currentTimeMillis()),
        endedAt = row.endedAt ?: row.startedAt ?: Time.toIso(System.currentTimeMillis()),
        treeKind = row.treeKind,
        createdAt = row.createdAt ?: Time.toIso(System.currentTimeMillis()),
        updatedAt = row.updatedAt ?: Time.toIso(System.currentTimeMillis()),
    )

    fun toUpdate(record: FocusRecord): FocusSessionUpdate = FocusSessionUpdate(
        title = cleanTitle(record.title),
        durationMinutes = cleanMinutes(record.durationMinutes, Limits.DEFAULT_DURATION, 1),
        actualMinutes = cleanMinutes(record.actualMinutes, record.durationMinutes, 0),
        status = record.status.wire,
        startedAt = Time.toIso(record.startedAt),
        endedAt = Time.toIso(record.endedAt),
        treeKind = record.treeKind,
        updatedAt = Time.toIso(record.updatedAt),
    )
}
