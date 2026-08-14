package com.example.timbertimer.data.model

import com.example.timbertimer.core.Palette
import com.example.timbertimer.core.Seed

/**
 * A project: a name, a colour, and the tree species its records are drawn with.
 *
 * Every record belongs to exactly one. Two exist from the start and cannot be
 * deleted — the default focus project and Rest — and records written before
 * projects existed are mapped to one derived from their title, so history keeps
 * working without a migration step.
 *
 * Ids are plain strings rather than UUIDs so the built-ins ("rest") and the
 * migrated ones ("t:deep focus") keep stable, meaningful keys that every device
 * arrives at independently.
 */
data class Project(
    val id: String,
    val name: String,
    val color: String,
    /** A [TreeSpecies] id; Rest legitimately carries the wilted one. */
    val tree: String,
    val sortOrder: Int,
    val createdAt: Long,
    val updatedAt: Long,
    /**
     * True for the grey placeholder a record gets when its project was deleted
     * on another device. It still renders; it just cannot be edited.
     */
    val missing: Boolean = false,
) {
    val species: TreeSpecies
        get() = TreeSpecies.byId(tree) ?: TreeSpecies.PINE

    val isBuiltIn: Boolean get() = id in Projects.BUILTIN_IDS
}

object Projects {

    const val DEFAULT_ID = "focus"
    const val REST_ID = "rest"
    val BUILTIN_IDS = setOf(DEFAULT_ID, REST_ID)

    /** Records made before projects existed key off their title with this prefix. */
    const val LEGACY_PREFIX = "t:"

    /** `projects.name` is `char_length(name) between 1 and 60`. */
    const val NAME_MAX = 60

    /** Built-in names are stored in English so a record means the same everywhere. */
    const val DEFAULT_NAME = "Focus"
    const val REST_NAME = "Rest"

    /** A Toggl-like palette: distinct at a glance, readable on both themes. */
    val COLORS = listOf(
        "#9e5bd9", "#0b83d9", "#d94182", "#e36a00",
        "#2da608", "#06a893", "#465bb3", "#c9806b",
        "#bf7000", "#c7af14", "#566614", "#d92b2b",
        "#e57cd8", "#3866a3", "#a5449e", "#525266",
    )

    const val REST_COLOR = "#a1866f"
    const val MISSING_COLOR = "#8e8e93"

    /**
     * A new project's colour and tree both come from its name, so naming it is
     * enough — and the same name always looks the same, on every device, with
     * nothing having to be written down first.
     */
    fun colorForName(name: String?): String = COLORS[Seed.colorIndexFor(name, COLORS.size)]

    fun treeForName(name: String?): String = Seed.defaultSpeciesFor(name).id

    /**
     * Walks on from the name's own colour until one is free, so two projects do
     * not end up sharing a colour in the chart and in the forest.
     */
    fun freeColorForName(name: String?, existing: List<Project>, skipId: String?): String {
        val used = existing.filter { it.id != skipId }.map { it.color }.toSet()
        val start = COLORS.indexOf(colorForName(name)).coerceAtLeast(0)
        for (step in COLORS.indices) {
            val color = COLORS[(start + step) % COLORS.size]
            if (color !in used) return color
        }
        return colorForName(name)
    }

    fun legacyIdForTitle(title: String?): String =
        LEGACY_PREFIX + (title?.trim()?.lowercase()?.ifEmpty { null } ?: "deep focus")

    /**
     * Which project a row belongs to. Rows written since projects exist carry
     * the id; older ones are mapped by their shape — a wilted tree that was not
     * an abandoned session was a rest, anything else keys off its title.
     *
     * [legacyStatus] is a column no record carries any more. It survives as a
     * parameter to read rows saved by a version that did — the ones still in
     * this device's own storage — because it is the only thing that tells an old
     * rest apart from an old abandoned session. Cloud rows had that answer
     * written into `project_id` by the migration in `docs/supabase-schema.sql`
     * before the column was dropped, so they never reach the second line.
     */
    fun resolveId(projectId: String?, legacyStatus: String?, treeKind: String?, title: String?): String {
        val stored = projectId?.trim().orEmpty()
        if (stored.isNotEmpty()) return stored
        if (legacyStatus != "abandoned" && treeKind == TreeSpecies.WILTED.label) return REST_ID
        return legacyIdForTitle(title)
    }

    fun normalize(
        id: String,
        name: String?,
        color: String?,
        tree: String?,
        sortOrder: Int,
        createdAt: Long,
        updatedAt: Long,
    ): Project {
        val safeName = name?.trim()?.take(NAME_MAX)?.ifBlank { null } ?: "Project"
        return Project(
            id = id,
            name = safeName,
            color = Palette.normalizeColor(color)
                ?: if (id == REST_ID) REST_COLOR else colorForName(safeName),
            tree = TreeSpecies.byId(tree)?.id ?: treeForName(safeName),
            sortOrder = sortOrder,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
    }

    /** The built-ins, seeded on a fresh install and never removed. */
    fun builtIn(id: String, now: Long): Project = when (id) {
        REST_ID -> Project(
            id = REST_ID,
            name = REST_NAME,
            color = REST_COLOR,
            tree = TreeSpecies.WILTED.id,
            sortOrder = 900,
            createdAt = now,
            updatedAt = now,
        )

        else -> Project(
            id = DEFAULT_ID,
            name = DEFAULT_NAME,
            color = COLORS[0],
            tree = TreeSpecies.PINE.id,
            sortOrder = 0,
            createdAt = now,
            updatedAt = now,
        )
    }

    fun missing(id: String?): Project = Project(
        id = id?.ifBlank { null } ?: DEFAULT_ID,
        name = "",
        color = MISSING_COLOR,
        tree = TreeSpecies.PINE.id,
        sortOrder = 999,
        createdAt = 0L,
        updatedAt = 0L,
        missing = true,
    )

    /** Focus first, Rest last, everything else by its saved position then name. */
    fun sorted(projects: List<Project>): List<Project> {
        fun rank(project: Project) = when (project.id) {
            DEFAULT_ID -> 0
            REST_ID -> 2
            else -> 1
        }
        return projects.sortedWith(
            compareBy({ rank(it) }, { it.sortOrder }, { it.name.lowercase() })
        )
    }
}

/**
 * An immutable snapshot of every project, with the lookups the UI needs.
 *
 * Handed around rather than queried from a repository so a screen cannot
 * accidentally read a half-updated list mid-recomposition, and so the forest,
 * the calendar and the chart all agree about what colour a record is.
 */
class ProjectBook(projects: List<Project>) {

    val all: List<Project> = Projects.sorted(projects)

    private val byId: Map<String, Project> = all.associateBy { it.id }

    /** Never null: a record pointing at a deleted project still renders, in grey. */
    operator fun get(id: String?): Project = byId[id] ?: Projects.missing(id)

    fun projectFor(record: FocusRecord): Project = get(record.projectId)

    /**
     * The species a record is *drawn* with. The project is the source of truth,
     * so changing its tree re-plants its whole forest; the kind stored on the
     * record is the fallback for one whose project has been deleted.
     */
    fun speciesFor(record: FocusRecord): TreeSpecies {
        val project = projectFor(record)
        if (!project.missing) return project.species
        return record.storedSpecies
    }

    fun contains(id: String?): Boolean = id != null && byId.containsKey(id)

    val isEmpty: Boolean get() = all.isEmpty()

    companion object {
        val EMPTY = ProjectBook(emptyList())
    }
}
