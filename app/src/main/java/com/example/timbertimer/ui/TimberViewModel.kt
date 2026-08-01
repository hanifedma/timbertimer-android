package com.example.timbertimer.ui

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.timbertimer.R
import com.example.timbertimer.core.Seed
import com.example.timbertimer.core.Time
import com.example.timbertimer.core.UiMessage
import com.example.timbertimer.data.RecordMapper
import com.example.timbertimer.data.TimberRepository
import com.example.timbertimer.data.local.SettingsStore
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.data.model.TimerMode
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.data.remote.MissingVerifierException
import com.example.timbertimer.data.remote.SupabaseAuth
import com.example.timbertimer.timer.TimerEngine
import com.example.timbertimer.timer.TimerFeedback
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Which forest window the user is looking at. */
enum class GroveView { TODAY, WEEK, MONTH }

/** The focus form, kept apart from the running timer it may go on to create. */
data class FocusForm(
    val title: String = "",
    val durationMinutes: Int = Limits.DEFAULT_DURATION,
    val mode: TimerMode = TimerMode.COUNTDOWN,
    val species: TreeSpecies = TreeSpecies.PINE,
)

data class GroveState(
    val view: GroveView = GroveView.WEEK,
    /** Start of the week or month being shown. */
    val anchor: Long = Time.startOfWeek(System.currentTimeMillis()),
)

data class RecordFilter(
    val query: String = "",
    val status: RecordStatus? = null,
)

/** The add/edit sheet's fields, as text so a half-typed value is not destroyed. */
data class RecordEditor(
    val id: String?,
    val title: String,
    val startedAt: String,
    val status: RecordStatus,
    val durationMinutes: String,
    val actualMinutes: String,
    val species: TreeSpecies,
)

class TimberViewModel(
    private val repository: TimberRepository,
    private val engine: TimerEngine,
    val settings: SettingsStore,
    private val auth: SupabaseAuth,
    private val feedback: TimerFeedback,
) : ViewModel() {

    val records = repository.records
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

    /** A screen the app was opened *at*, e.g. by the widget. Consumed once. */
    private val _requestedDestination = MutableStateFlow<String?>(null)
    val requestedDestination: StateFlow<String?> = _requestedDestination.asStateFlow()

    private val _editor = MutableStateFlow<RecordEditor?>(null)
    val editor: StateFlow<RecordEditor?> = _editor.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    /** True once history has seeded the form, so it only happens on first load. */
    private var formSeeded = false

    init {
        _form.value = FocusForm(
            title = settings.sessionName.value.ifBlank { Limits.DEFAULT_TITLE },
            durationMinutes = settings.duration.value,
            mode = settings.timerMode.value,
            species = repository.resolveSpeciesFor(settings.sessionName.value),
        )

        viewModelScope.launch { repository.messages.collect { _messages.emit(it) } }
        viewModelScope.launch { engine.messages.collect { _messages.emit(it) } }

        // The name and its tree both depend on history, which arrives later.
        viewModelScope.launch {
            repository.records.collect { records ->
                if (formSeeded) return@collect
                formSeeded = true
                val title = when {
                    settings.hasSessionName() -> settings.sessionName.value
                    else -> records.firstOrNull { !it.isRest }?.title ?: Limits.DEFAULT_TITLE
                }
                _form.value = _form.value.copy(
                    title = title,
                    species = repository.resolveSpeciesFor(title),
                )
            }
        }

        // A timer adopted from another device names the session and fixes its tree.
        viewModelScope.launch {
            engine.timer.collect { running ->
                if (running == null) return@collect
                val species = TreeSpecies.byId(running.speciesId)
                    ?: repository.resolveSpeciesFor(running.title)
                _form.value = _form.value.copy(
                    title = running.title,
                    mode = running.mode,
                    species = species,
                )
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
        // A running timer owns its name and its tree; typing does not move them.
        val species = if (timer.value != null) _form.value.species
        else repository.resolveSpeciesFor(trimmed)
        _form.value = _form.value.copy(title = trimmed, species = species)
        settings.setSessionName(trimmed)
    }

    fun setDuration(minutes: Int) {
        val safe = minutes.coerceIn(1, Limits.MINUTES_MAX)
        _form.value = _form.value.copy(durationMinutes = safe)
        settings.setDuration(safe)
    }

    fun setMode(mode: TimerMode) {
        if (timer.value != null) return
        _form.value = _form.value.copy(mode = mode)
        settings.setTimerMode(mode)
    }

    fun setSpecies(species: TreeSpecies) {
        if (timer.value != null) return
        _form.value = _form.value.copy(species = species)
        // Remembering the pick per name is what makes "eating ayam" keep its palm.
        settings.saveTreePreference(_form.value.title.ifBlank { Limits.DEFAULT_TITLE }, species.id)
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
                speciesId = form.species.id,
            )
        }
    }

    fun finish() {
        viewModelScope.launch { engine.finish() }
    }

    fun startRest() {
        viewModelScope.launch { engine.startRest() }
    }

    fun finishRest() {
        viewModelScope.launch { engine.finishRest() }
    }

    // ---------- forest ----------

    fun setGroveView(view: GroveView) {
        val nowMillis = System.currentTimeMillis()
        _grove.value = GroveState(
            view = view,
            anchor = when (view) {
                GroveView.TODAY -> Time.startOfDay(nowMillis)
                GroveView.WEEK -> Time.startOfWeek(nowMillis)
                GroveView.MONTH -> Time.startOfMonth(nowMillis)
            },
        )
    }

    fun shiftGrove(direction: Int) {
        val state = _grove.value
        _grove.value = when (state.view) {
            GroveView.TODAY -> state
            GroveView.WEEK -> state.copy(anchor = Time.addDays(state.anchor, direction * 7L))
            GroveView.MONTH -> state.copy(
                anchor = Time.startOfMonth(Time.addMonths(state.anchor, direction.toLong()))
            )
        }
    }

    fun resetGroveToCurrent() = setGroveView(_grove.value.view)

    // ---------- records ----------

    fun setQuery(query: String) {
        _filter.value = _filter.value.copy(query = query)
    }

    fun setStatusFilter(status: RecordStatus?) {
        _filter.value = _filter.value.copy(status = status)
    }

    fun visibleRecords(): List<FocusRecord> {
        val (query, status) = _filter.value
        val needle = query.trim().lowercase()
        return records.value
            .asSequence()
            .filter { status == null || it.status == status }
            .filter { needle.isEmpty() || it.title.lowercase().contains(needle) }
            .sortedByDescending { it.startedAt }
            .toList()
    }

    fun openEditor(record: FocusRecord?) {
        val nowMillis = System.currentTimeMillis()
        _editor.value = if (record == null) {
            val title = _form.value.title.ifBlank { Limits.DEFAULT_TITLE }
            RecordEditor(
                id = null,
                title = title,
                startedAt = Time.editableTimestamp(nowMillis),
                status = RecordStatus.COMPLETED,
                durationMinutes = _form.value.durationMinutes.toString(),
                actualMinutes = _form.value.durationMinutes.toString(),
                species = repository.resolveSpeciesFor(title),
            )
        } else {
            RecordEditor(
                id = record.id,
                title = record.title,
                startedAt = Time.editableTimestamp(record.startedAt),
                status = record.status,
                durationMinutes = record.durationMinutes.toString(),
                actualMinutes = record.actualMinutes.toString(),
                species = TreeSpecies.byLabel(record.treeKind)
                    ?.takeIf { it != TreeSpecies.WILTED }
                    ?: repository.resolveSpeciesFor(record.title),
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
    fun editorIsValid(editor: RecordEditor): Boolean =
        editor.title.isNotBlank() && Time.parseEditableTimestamp(editor.startedAt) != null

    fun saveEditor() {
        val editor = _editor.value ?: return
        val startedAt = Time.parseEditableTimestamp(editor.startedAt) ?: return
        val title = RecordMapper.cleanTitle(editor.title)
        val actual = RecordMapper.cleanMinutes(editor.actualMinutes.toIntOrNull(), 0, 0)
        val duration = RecordMapper.cleanMinutes(
            editor.durationMinutes.toIntOrNull(),
            if (actual > 0) actual else 1,
            1,
        )

        val record = FocusRecord(
            id = editor.id ?: UUID.randomUUID().toString(),
            title = title,
            durationMinutes = duration,
            actualMinutes = actual,
            status = editor.status,
            startedAt = startedAt,
            endedAt = startedAt + actual * 60_000L,
            treeKind = RecordMapper.pickTreeKind(title, editor.status, editor.species.id) {
                settings.treePreference(it)
            },
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )

        // Remembering the species here too, so the timer's picker agrees with
        // what the user just said this session's tree should be.
        if (editor.status == RecordStatus.COMPLETED) {
            settings.saveTreePreference(title, editor.species.id)
        }

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

    // ---------- to-do ----------

    fun addNote(text: String) {
        viewModelScope.launch { repository.addNote(text) }
    }

    fun toggleNote(id: String) {
        viewModelScope.launch { repository.toggleNote(id) }
    }

    fun deleteNote(id: String) {
        viewModelScope.launch { repository.deleteNote(id) }
    }

    fun reorderNotes(orderedIds: List<String>) {
        viewModelScope.launch { repository.reorderNotes(orderedIds) }
    }

    // ---------- account ----------

    /** The URL to open in a Custom Tab, or null when something went wrong. */
    suspend fun authorizeUrl(): String? =
        runCatching { auth.buildAuthorizeUrl() }.getOrNull()

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

    /** Auditions the chime at the volume just chosen. */
    fun previewSound() {
        feedback.playPreview()
    }

    /** The species a name would grow, used to preview the picker's default. */
    fun speciesFor(title: String): TreeSpecies = repository.resolveSpeciesFor(Seed.treeSeed(title))

    companion object {
        fun factory(
            repository: TimberRepository,
            engine: TimerEngine,
            settings: SettingsStore,
            auth: SupabaseAuth,
            feedback: TimerFeedback,
        ) = viewModelFactory {
            initializer { TimberViewModel(repository, engine, settings, auth, feedback) }
        }
    }
}

