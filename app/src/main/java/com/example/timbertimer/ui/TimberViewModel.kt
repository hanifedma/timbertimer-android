package com.example.timbertimer.ui

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.timbertimer.R
import com.example.timbertimer.core.Time
import com.example.timbertimer.core.UiMessage
import com.example.timbertimer.data.RecordMapper
import com.example.timbertimer.data.TimberRepository
import com.example.timbertimer.data.local.RestAlertStyle
import com.example.timbertimer.data.local.SettingsStore
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.NoteList
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.TimerMode
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.data.remote.GoogleSignIn
import com.example.timbertimer.data.remote.MissingVerifierException
import com.example.timbertimer.data.remote.SupabaseAuth
import com.example.timbertimer.timer.TimerEngine
import com.example.timbertimer.timer.TimerFeedback
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToLong

/** Which forest window the user is looking at. */
enum class GroveView { DAY, WEEK, MONTH }

/**
 * The focus form, kept apart from the running timer it may go on to create.
 *
 * The rest's two fields live here rather than in a form of their own: they are
 * the same kind of thing — what the next one will be, not what a running one
 * is — and the panel that reads them sits on this screen.
 */
data class FocusForm(
    val title: String = "",
    val durationMinutes: Int = Limits.DEFAULT_DURATION,
    val mode: TimerMode = TimerMode.COUNTDOWN,
    val projectId: String = Projects.DEFAULT_ID,
    val restMode: TimerMode = TimerMode.COUNTDOWN,
    val restMinutes: Int = Limits.DEFAULT_REST_DURATION,
)

data class GroveState(
    val view: GroveView = GroveView.DAY,
    /** Start of the day, week or month being shown. */
    val anchor: Long = Time.startOfDay(System.currentTimeMillis()),
)

data class RecordFilter(val query: String = "")

/**
 * The add/edit sheet's fields.
 *
 * Times are held as instants rather than text: they are set with the platform's
 * own date and time pickers, and the calendar seeds them from wherever it was
 * tapped, so there is never a half-typed value to preserve. How long the record
 * is, is the gap between the two — never a number typed separately, because two
 * fields that can disagree eventually will.
 */
data class RecordEditor(
    val id: String?,
    val title: String,
    val projectId: String,
    val startedAt: Long,
    val endedAt: Long,
) {
    val minutes: Int
        get() = ((endedAt - startedAt) / 60_000L).coerceIn(0L, Limits.MINUTES_MAX.toLong()).toInt()

    val endsBeforeStart: Boolean get() = endedAt < startedAt

    val tooLong: Boolean get() = (endedAt - startedAt) > Limits.MINUTES_MAX * 60_000L
}

/**
 * The project sheet.
 *
 * [autoColor] and [autoTree] track whether the look is still following the name.
 * A project being created re-rolls both as it is typed, so naming it is enough;
 * touching either picker pins that choice and stops the re-rolling.
 */
data class ProjectEditor(
    val id: String?,
    val name: String,
    val color: String,
    val tree: String,
    val autoColor: Boolean,
    val autoTree: Boolean,
) {
    val isNew: Boolean get() = id == null
    val isBuiltIn: Boolean get() = id in Projects.BUILTIN_IDS
}

/**
 * A calendar drag waiting to be confirmed.
 *
 * A block is easy to move without quite meaning to, and the times it lands on
 * are not something you can read back off a grid precisely — so the change is
 * described in words and only written once it has been agreed to, exactly as on
 * the website.
 */
data class PendingMove(
    val record: FocusRecord,
    val startedAt: Long,
    val minutes: Int,
    /** True when an edge was dragged rather than the whole block. */
    val resized: Boolean,
) {
    val endedAt: Long get() = startedAt + minutes * 60_000L
}

/** What the calendar is showing: how many days, how tall an hour, starting when. */
data class CalendarState(
    val days: Int = SettingsStore.CALENDAR_DEFAULT_DAYS,
    val zoomDp: Float = SettingsStore.CALENDAR_DEFAULT_ZOOM,
    val anchor: Long = 0L,
)

class TimberViewModel(
    private val repository: TimberRepository,
    private val engine: TimerEngine,
    val settings: SettingsStore,
    private val auth: SupabaseAuth,
    private val feedback: TimerFeedback,
    private val googleSignIn: GoogleSignIn,
) : ViewModel() {

    val records = repository.records
    val projects = repository.projects
    val projectsSyncBlocked = repository.projectsSyncBlocked
    val notes = repository.notes
    val dataMode = repository.dataMode
    val session = repository.session

    val timer = engine.timer
    val rest = engine.rest
    val now = engine.now

    private val _form = MutableStateFlow(FocusForm())
    val form: StateFlow<FocusForm> = _form.asStateFlow()

    private val _grove = MutableStateFlow(GroveState())
    val grove: StateFlow<GroveState> = _grove.asStateFlow()

    private val _filter = MutableStateFlow(RecordFilter())
    val filter: StateFlow<RecordFilter> = _filter.asStateFlow()

    private val _calendar = MutableStateFlow(
        CalendarState(
            days = settings.calendarDays.value,
            zoomDp = settings.calendarZoom.value,
            anchor = defaultCalendarAnchor(settings.calendarDays.value),
        )
    )
    val calendar: StateFlow<CalendarState> = _calendar.asStateFlow()

    /** A screen the app was opened *at*, e.g. by the widget. Consumed once. */
    private val _requestedDestination = MutableStateFlow<String?>(null)
    val requestedDestination: StateFlow<String?> = _requestedDestination.asStateFlow()

    private val _editor = MutableStateFlow<RecordEditor?>(null)
    val editor: StateFlow<RecordEditor?> = _editor.asStateFlow()

    private val _projectEditor = MutableStateFlow<ProjectEditor?>(null)
    val projectEditor: StateFlow<ProjectEditor?> = _projectEditor.asStateFlow()

    private val _pendingMove = MutableStateFlow<PendingMove?>(null)
    val pendingMove: StateFlow<PendingMove?> = _pendingMove.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    /** True once history has seeded the form, so it only happens on first load. */
    private var formSeeded = false

    init {
        _form.value = FocusForm(
            title = settings.sessionName.value.ifBlank { Limits.DEFAULT_TITLE },
            durationMinutes = settings.duration.value,
            mode = settings.timerMode.value,
            projectId = settings.selectedProjectId.value.ifBlank { Projects.DEFAULT_ID },
            restMode = settings.restMode.value,
            restMinutes = settings.restDuration.value,
        )

        viewModelScope.launch { repository.messages.collect { _messages.emit(it) } }
        viewModelScope.launch { engine.messages.collect { _messages.emit(it) } }

        // The name and its project both depend on history and on the project
        // list, and both arrive later. Seeding before the projects are in would
        // find nothing to match and fall back to the default — overwriting the
        // very choice it is supposed to restore — so it waits for both.
        viewModelScope.launch {
            combine(repository.records, repository.projects, ::Pair).collect { (records, book) ->
                if (formSeeded || book.isEmpty || engine.timer.value != null) return@collect
                formSeeded = true
                val latestFocus = records.firstOrNull { !it.isRest }
                val title = when {
                    settings.hasSessionName() -> settings.sessionName.value
                    else -> latestFocus?.title ?: Limits.DEFAULT_TITLE
                }
                // What was last chosen wins; then the task name's own project;
                // then wherever the last session was filed.
                val paired = settings.selectedProjectId.value.takeIf { book.contains(it) }
                    ?: repository.projectForTitle(title)
                    ?: latestFocus?.projectId?.takeIf { book.contains(it) }
                    ?: Projects.DEFAULT_ID
                _form.value = _form.value.copy(title = title, projectId = paired)
                if (settings.selectedProjectId.value != paired) settings.setSelectedProjectId(paired)
            }
        }

        // A timer adopted from another device names the session, and brings its
        // project (and therefore its tree) with it.
        viewModelScope.launch {
            engine.timer.collect { running ->
                if (running == null) return@collect
                formSeeded = true
                _form.value = _form.value.copy(
                    title = running.title,
                    mode = running.mode,
                    projectId = running.projectId,
                )
                if (settings.selectedProjectId.value != running.projectId) {
                    settings.setSelectedProjectId(running.projectId)
                }
            }
        }

        // A project deleted on another device must not stay selected here.
        viewModelScope.launch {
            repository.projects.collect { book ->
                if (book.isEmpty) return@collect
                if (!book.contains(_form.value.projectId)) {
                    setProject(Projects.DEFAULT_ID)
                }
            }
        }
    }

    fun requestDestination(destination: String) {
        _requestedDestination.value = destination
    }

    fun consumeDestination() {
        _requestedDestination.value = null
    }

    // ---------- lifecycle ----------

    fun onResume() {
        engine.setForeground(true)
        viewModelScope.launch {
            repository.refresh()
            engine.hydrate()
        }
    }

    fun onPause() {
        engine.setForeground(false)
    }

    // ---------- the focus form ----------

    fun setTitle(title: String) {
        val trimmed = title.take(Limits.TITLE_MAX)
        _form.value = _form.value.copy(title = trimmed)
        // A running stopwatch is renamed in place, not just remembered for
        // next time — and auto-matching a project from the title, below, is a
        // start-time convenience that a running session should not have
        // sprung on it by a keystroke that happens to match a known task.
        // A running countdown owns its name outright; see sessionEditsAllowed.
        if (timer.value != null) {
            engine.updateRunning(title = trimmed)
            return
        }
        settings.setSessionName(trimmed)
        // Follow the task name that was just typed or picked, if it has a home.
        repository.projectForTitle(trimmed)?.let { setProject(it) }
    }

    /**
     * Whether the task name, project and tree can be changed right now.
     *
     * A stopwatch is open-ended, so what the session *is* can still be
     * decided while it runs; a countdown's identity is fixed the moment it
     * starts. Mirrors the web client's `sessionEditsAllowed`.
     */
    fun sessionEditsAllowed(): Boolean {
        val running = timer.value ?: return true
        return running.mode == TimerMode.STOPWATCH
    }

    fun setDuration(minutes: Int) {
        val safe = minutes.coerceIn(1, Limits.TIMER_MINUTES_MAX)
        _form.value = _form.value.copy(durationMinutes = safe)
        settings.setDuration(safe)
    }

    fun setMode(mode: TimerMode) {
        if (timer.value != null) return
        _form.value = _form.value.copy(mode = mode)
        settings.setTimerMode(mode)
    }

    fun setProject(id: String) {
        if (!sessionEditsAllowed()) return
        _form.value = _form.value.copy(projectId = id)
        settings.setSelectedProjectId(id)
        // The tree belongs to the project, so moving a running stopwatch to a
        // different one changes what it's growing too — engine.updateRunning
        // is where that actually happens; see TimerEngine.
        if (timer.value != null) engine.updateRunning(projectId = id)
    }

    /**
     * The tree belongs to the project now, so choosing one here re-plants every
     * record that project ever grew.
     *
     * Follows the same rule the rest of the session's identity does while a
     * timer runs — see [sessionEditsAllowed]. A project's tree can still be
     * changed from the project sheet either way, which is not tied to what is
     * running.
     */
    fun setProjectTree(species: TreeSpecies) {
        if (!sessionEditsAllowed()) return
        val project = projects.value[_form.value.projectId]
        if (project.missing || project.tree == species.id) return
        viewModelScope.launch { repository.saveProject(project.copy(tree = species.id)) }
    }

    /** Names already used, newest first, offered as suggestions. */
    fun titleSuggestions(): List<String> =
        records.value
            .asSequence()
            .filterNot { it.isRest }
            .sortedByDescending { it.startedAt }
            .map { it.title.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .take(8)
            .toList()

    // ---------- the timer ----------

    fun start() {
        val form = _form.value
        viewModelScope.launch {
            engine.start(
                title = form.title,
                mode = form.mode,
                minutes = form.durationMinutes,
                projectId = form.projectId,
            )
        }
    }

    fun finish() {
        viewModelScope.launch { engine.finish() }
    }

    // ---------- the rest ----------

    fun setRestMode(mode: TimerMode) {
        if (rest.value != null) return
        _form.value = _form.value.copy(restMode = mode)
        settings.setRestMode(mode)
    }

    fun setRestDuration(minutes: Int) {
        if (rest.value != null) return
        // Clamped for the form the same way the engine clamps it on the way in,
        // so the number on screen is the number that will run. The box itself
        // accepts up to three digits, and 999 is not a rest.
        val safe = minutes.coerceIn(1, Limits.TIMER_MINUTES_MAX)
        _form.value = _form.value.copy(restMinutes = safe)
        settings.setRestDuration(safe)
    }

    fun startRest() {
        val form = _form.value
        viewModelScope.launch { engine.startRest(form.restMode, form.restMinutes) }
    }

    fun finishRest() {
        viewModelScope.launch { engine.finishRest() }
    }

    /**
     * Auditions the choice as it is made, the way the volume slider does.
     *
     * A setting whose whole subject is "how loud and how insistent" is one
     * nobody can evaluate by reading four words — and the one time it normally
     * plays is the one time they cannot be experimenting with it.
     */
    fun setRestAlert(style: RestAlertStyle) {
        settings.setRestAlert(style)
        feedback.previewRestAlarm(style)
    }

    // ---------- forest ----------

    fun setGroveView(view: GroveView) {
        val nowMillis = System.currentTimeMillis()
        _grove.value = GroveState(
            view = view,
            anchor = when (view) {
                GroveView.DAY -> Time.startOfDay(nowMillis)
                GroveView.WEEK -> Time.startOfWeek(nowMillis)
                GroveView.MONTH -> Time.startOfMonth(nowMillis)
            },
        )
    }

    fun shiftGrove(direction: Int) {
        val state = _grove.value
        _grove.value = when (state.view) {
            GroveView.DAY -> state.copy(anchor = Time.addDays(state.anchor, direction.toLong()))
            GroveView.WEEK -> state.copy(anchor = Time.addDays(state.anchor, direction * 7L))
            GroveView.MONTH -> state.copy(
                anchor = Time.startOfMonth(Time.addMonths(state.anchor, direction.toLong()))
            )
        }
    }

    fun resetGroveToCurrent() = setGroveView(_grove.value.view)

    // ---------- the calendar ----------

    fun setCalendarDays(days: Int) {
        val safe = days.coerceIn(1, SettingsStore.CALENDAR_MAX_DAYS)
        if (safe == _calendar.value.days) return
        settings.setCalendarDays(safe)
        // Keep today on screen if it already was, rather than letting it drift
        // away as the range grows or shrinks.
        val showedToday = calendarShowsToday()
        _calendar.value = _calendar.value.copy(
            days = safe,
            anchor = if (showedToday) defaultCalendarAnchor(safe) else _calendar.value.anchor,
        )
    }

    fun zoomCalendar(factor: Float) {
        val next = (_calendar.value.zoomDp * factor)
            .coerceIn(SettingsStore.CALENDAR_MIN_ZOOM, SettingsStore.CALENDAR_MAX_ZOOM)
        if (next == _calendar.value.zoomDp) return
        settings.setCalendarZoom(next)
        _calendar.value = _calendar.value.copy(zoomDp = next)
    }

    fun shiftCalendar(direction: Int) {
        val state = _calendar.value
        _calendar.value = state.copy(anchor = Time.addDays(state.anchor, direction.toLong() * state.days))
    }

    fun calendarToToday() {
        _calendar.value = _calendar.value.copy(anchor = defaultCalendarAnchor(_calendar.value.days))
    }

    fun calendarShowsToday(): Boolean {
        val state = _calendar.value
        val today = Time.startOfDay(System.currentTimeMillis())
        return today >= state.anchor && today < Time.addDays(state.anchor, state.days.toLong())
    }

    /** Today sits in the middle of the range, so yesterday and the rest of today
     *  are both one glance away. */
    private fun defaultCalendarAnchor(days: Int): Long =
        Time.addDays(Time.startOfDay(System.currentTimeMillis()), -((days - 1) / 2).toLong())

    /**
     * Asks about a block dragged to a new time or resized on the calendar.
     *
     * The start lands on a whole minute: a record made by the timer starts at
     * some stray second, and carrying that through a drag makes the times read a
     * minute out from the grid line it was dropped on.
     *
     * Nothing is written here. A drag that changed nothing is dropped silently;
     * anything else waits for [confirmMove], and until then the block on screen
     * is still drawn from the record's unchanged times.
     */
    fun proposeMove(record: FocusRecord, startedAt: Long, minutes: Int, resized: Boolean) {
        val start = (startedAt / 60_000L) * 60_000L
        val safeMinutes = minutes.coerceIn(1, Limits.MINUTES_MAX)
        if (record.startedAt == start && record.actualMinutes == safeMinutes) return
        _pendingMove.value = PendingMove(record, start, safeMinutes, resized)
    }

    fun cancelMove() {
        _pendingMove.value = null
    }

    fun confirmMove() {
        val move = _pendingMove.value ?: return
        _pendingMove.value = null
        viewModelScope.launch {
            repository.updateRecord(
                move.record.copy(
                    startedAt = move.startedAt,
                    endedAt = move.endedAt,
                    actualMinutes = move.minutes,
                )
            )
            _messages.emit(UiMessage.of(R.string.toast_record_saved))
        }
    }

    // ---------- records ----------

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    /** Searching covers the project name too, now that it is part of a record. */
    fun visibleRecords(): List<FocusRecord> {
        val needle = _filter.value.query.trim().lowercase()
        val book = projects.value
        return records.value
            .asSequence()
            .filter {
                needle.isEmpty() ||
                    "${it.title} ${book[it.projectId].name}".lowercase().contains(needle)
            }
            .sortedByDescending { it.startedAt }
            .toList()
    }

    /**
     * Opens the record sheet. [startedAt] and [minutes] seed a new record with
     * the slot the calendar was dragged over.
     */
    fun openEditor(
        record: FocusRecord?,
        startedAt: Long? = null,
        minutes: Int? = null,
        projectId: String? = null,
    ) {
        _editor.value = if (record == null) {
            val start = startedAt ?: roundToQuarter(System.currentTimeMillis())
            val length = (minutes ?: _form.value.durationMinutes).coerceIn(1, Limits.MINUTES_MAX)
            RecordEditor(
                id = null,
                title = "",
                projectId = projectId
                    ?: _form.value.projectId.takeIf { projects.value.contains(it) }
                    ?: Projects.DEFAULT_ID,
                startedAt = start,
                endedAt = start + length * 60_000L,
            )
        } else {
            RecordEditor(
                id = record.id,
                title = record.title,
                projectId = record.projectId,
                startedAt = record.startedAt,
                endedAt = maxOf(record.endedAt, record.startedAt),
            )
        }
    }

    fun updateEditor(editor: RecordEditor) {
        _editor.value = editor
    }

    fun closeEditor() {
        _editor.value = null
    }

    /** True when the sheet's values would produce a record the table accepts. */
    fun editorIsValid(editor: RecordEditor): Boolean = !editor.endsBeforeStart && !editor.tooLong

    fun saveEditor() {
        val editor = _editor.value ?: return
        if (!editorIsValid(editor)) return

        val book = projects.value
        val project = book[editor.projectId]
        // An empty task name falls back to the project's, so a block dragged out
        // on the calendar is nameable with one tap and no typing.
        val title = RecordMapper.cleanTitle(editor.title.ifBlank { project.name })
        val actual = editor.minutes

        val record = FocusRecord(
            id = editor.id ?: UUID.randomUUID().toString(),
            title = title,
            projectId = editor.projectId,
            actualMinutes = actual,
            startedAt = editor.startedAt,
            // Stored as exactly the minutes we keep, so the calendar block and
            // the "focused" figure can never disagree.
            endedAt = editor.startedAt + actual * 60_000L,
            treeKind = RecordMapper.pickTreeKind(project),
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

        viewModelScope.launch {
            if (editor.id == null) repository.createRecord(record) else repository.updateRecord(record)
            _messages.emit(UiMessage.of(R.string.toast_record_saved))
        }
        _editor.value = null
    }

    fun deleteRecord(record: FocusRecord) {
        viewModelScope.launch { repository.deleteRecord(record) }
    }

    fun deleteAllRecords() {
        viewModelScope.launch { repository.deleteAllRecords() }
    }

    // ---------- projects ----------

    fun openProjectEditor(project: Project?) {
        _projectEditor.value = if (project == null) {
            ProjectEditor(
                id = null,
                name = "",
                color = Projects.freeColorForName("", projects.value.all, null),
                tree = Projects.treeForName(""),
                autoColor = true,
                autoTree = true,
            )
        } else {
            ProjectEditor(
                id = project.id,
                name = project.name,
                color = project.color,
                tree = project.tree,
                autoColor = false,
                autoTree = false,
            )
        }
    }

    fun closeProjectEditor() {
        _projectEditor.value = null
    }

    /**
     * Typing a name re-rolls the look, so "Reading" always arrives as the same
     * colour and species without anyone having to choose.
     */
    fun setProjectEditorName(name: String) {
        val editor = _projectEditor.value ?: return
        val trimmed = name.take(Projects.NAME_MAX)
        _projectEditor.value = editor.copy(
            name = trimmed,
            color = if (editor.autoColor) {
                Projects.freeColorForName(trimmed.trim(), projects.value.all, editor.id)
            } else editor.color,
            tree = if (editor.autoTree) Projects.treeForName(trimmed.trim()) else editor.tree,
        )
    }

    fun setProjectEditorColor(color: String) {
        val editor = _projectEditor.value ?: return
        _projectEditor.value = editor.copy(color = color, autoColor = false)
    }

    fun setProjectEditorTree(species: TreeSpecies) {
        val editor = _projectEditor.value ?: return
        _projectEditor.value = editor.copy(tree = species.id, autoTree = false)
    }

    fun projectNameIsTaken(editor: ProjectEditor): Boolean {
        val name = editor.name.trim().lowercase()
        if (name.isEmpty()) return false
        return projects.value.all.any { it.id != editor.id && it.name.trim().lowercase() == name }
    }

    fun projectEditorIsValid(editor: ProjectEditor): Boolean =
        editor.name.isNotBlank() && !projectNameIsTaken(editor)

    fun saveProjectEditor() {
        val editor = _projectEditor.value ?: return
        if (!projectEditorIsValid(editor)) return

        val existing = editor.id?.let { id -> projects.value.all.firstOrNull { it.id == id } }
        val now = System.currentTimeMillis()
        val project = Project(
            id = editor.id ?: UUID.randomUUID().toString(),
            name = editor.name.trim().take(Projects.NAME_MAX),
            color = editor.color,
            tree = editor.tree,
            sortOrder = existing?.sortOrder ?: projects.value.all.size,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )

        viewModelScope.launch {
            repository.saveProject(project)
            // Saving re-sorts the list, so a new project is followed by id.
            if (editor.id == null && timer.value == null) setProject(project.id)
            _messages.emit(UiMessage.of(R.string.toast_project_saved))
        }
        _projectEditor.value = null
    }

    /** How many records would move if this project were deleted. */
    fun recordCountFor(projectId: String): Int =
        records.value.count { it.projectId == projectId }

    fun deleteProject(id: String) {
        if (id in Projects.BUILTIN_IDS) {
            _messages.tryEmit(UiMessage.of(R.string.toast_project_builtin))
            return
        }
        viewModelScope.launch {
            repository.deleteProject(id)
            _messages.emit(UiMessage.of(R.string.toast_project_deleted))
        }
        _projectEditor.value = null
    }

    // ---------- to-do ----------

    fun addNote(text: String, list: NoteList = NoteList.GENERAL) {
        viewModelScope.launch { repository.addNote(text, list) }
    }

    fun toggleNote(id: String) {
        viewModelScope.launch { repository.toggleNote(id) }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch { repository.deleteNote(id) }
    }

    fun moveNote(id: String, targetList: NoteList) {
        viewModelScope.launch { repository.moveNote(id, targetList) }
    }

    fun reorderNotes(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderNotes(orderedIds) }
    }

    // ---------- the Today list's day ----------

    private val _todayNotesAnchor = MutableStateFlow(Time.startOfDay(System.currentTimeMillis()))
    val todayNotesAnchor: StateFlow<Long> = _todayNotesAnchor.asStateFlow()

    /** The last "today" seen, so a real midnight rollover — advance the anchor,
     *  but only if it was following today live — can be told apart from having
     *  deliberately stepped back to browse an earlier day. */
    private var todayNotesKnownDateKey = Time.localDateKey(System.currentTimeMillis())

    fun shiftTodayNotes(direction: Int) {
        val next = Time.addDays(_todayNotesAnchor.value, direction.toLong())
        val today = Time.startOfDay(System.currentTimeMillis())
        // Stepping forward can never pass today; there is nothing there yet.
        _todayNotesAnchor.value = if (next > today) today else next
    }

    fun resetTodayNotesToToday() {
        _todayNotesAnchor.value = Time.startOfDay(System.currentTimeMillis())
    }

    /** Called on every clock tick; a no-op unless the day has actually turned. */
    fun checkTodayNotesRollover() {
        val todayKey = Time.localDateKey(System.currentTimeMillis())
        if (todayKey == todayNotesKnownDateKey) return
        val wasViewingToday = Time.localDateKey(_todayNotesAnchor.value) == todayNotesKnownDateKey
        todayNotesKnownDateKey = todayKey
        if (wasViewingToday) _todayNotesAnchor.value = Time.startOfDay(System.currentTimeMillis())
    }

    // ---------- account ----------

    /** The URL to open in a Custom Tab, or null when something went wrong. */
    suspend fun authorizeUrl(): String? =
        runCatching { auth.buildAuthorizeUrl() }.getOrNull()

    /** Lets the activity, which owns the sign-in sheet, speak in the app's voice. */
    fun notify(@StringRes message: Int) {
        _messages.tryEmit(UiMessage.of(message))
    }

    /**
     * Finishes a sign-in that Google completed on-device.
     *
     * Returns false when Supabase refuses the token — almost always because this
     * client id is not listed under the Google provider's *Authorized Client
     * IDs* — so the caller can fall back to the browser rather than leaving the
     * user staring at a sheet that did nothing.
     */
    suspend fun completeGoogleSignIn(idToken: String, rawNonce: String): Boolean =
        runCatching { auth.exchangeGoogleIdToken(idToken, rawNonce) }
            .onSuccess {
                _messages.emit(UiMessage.of(R.string.toast_signed_in, it.label))
                repository.refresh()
                engine.hydrate()
            }
            .isSuccess

    fun handleAuthCallback(uri: Uri) {
        viewModelScope.launch {
            when (val result = auth.readCallback(uri)) {
                is SupabaseAuth.CallbackResult.Failed -> _messages.emit(
                    UiMessage.of(R.string.auth_cancelled)
                )

                is SupabaseAuth.CallbackResult.Code -> {
                    val session = runCatching { auth.exchangeCode(result.value) }
                    session
                        .onSuccess {
                            _messages.emit(UiMessage.of(R.string.toast_signed_in, it.label))
                            repository.refresh()
                            engine.hydrate()
                        }
                        .onFailure { error ->
                            _messages.emit(
                                UiMessage.of(
                                    if (error is MissingVerifierException) R.string.auth_verify_failed
                                    else R.string.toast_cloud_load_fail
                                )
                            )
                        }
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            auth.signOut()
            // Otherwise the credential sheet reuses the account just left behind
            // without ever showing the chooser, and signing in as someone else
            // becomes impossible from inside the app.
            googleSignIn.forgetAccount()
            _messages.emit(UiMessage.of(R.string.toast_signed_out))
        }
    }

    fun toggleSound() {
        val enabled = !settings.soundEnabled.value
        settings.setSoundEnabled(enabled)
        _messages.tryEmit(
            UiMessage.of(if (enabled) R.string.toast_sound_on else R.string.toast_sound_off)
        )
    }

    fun setIdleReminder(enabled: Boolean) {
        settings.setIdleReminder(enabled)
        // Turning it off should clear anything already sitting in the shade.
        if (!enabled) engine.clearIdleNudge()
    }

    fun setBackgroundSync(enabled: Boolean) {
        settings.setBackgroundSync(enabled)
        // Start or stop the service now rather than at the next timer change.
        engine.onBackgroundSyncChanged()
    }

    /** Auditions the chime at the volume just chosen. */
    fun previewSound() {
        feedback.playPreview()
    }

    /** New records snap to a quarter hour, which is where people actually put them. */
    private fun roundToQuarter(millis: Long): Long {
        val quarter = 15 * 60_000L
        return (millis.toDouble() / quarter).roundToLong() * quarter
    }

    companion object {
        fun factory(
            repository: TimberRepository,
            engine: TimerEngine,
            settings: SettingsStore,
            auth: SupabaseAuth,
            feedback: TimerFeedback,
            googleSignIn: GoogleSignIn,
        ) = viewModelFactory {
            initializer {
                TimberViewModel(repository, engine, settings, auth, feedback, googleSignIn)
            }
        }
    }
}
