package com.example.timbertimer.data

import com.example.timbertimer.R
import com.example.timbertimer.core.Time
import com.example.timbertimer.core.UiMessage
import com.example.timbertimer.data.local.LocalStore
import com.example.timbertimer.data.local.SettingsStore
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.DataMode
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.Note
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.data.model.TimerMode
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.data.remote.ActiveTimerUpsert
import com.example.timbertimer.data.remote.NoteDoneUpdate
import com.example.timbertimer.data.remote.NoteRow
import com.example.timbertimer.data.remote.NoteUpsert
import com.example.timbertimer.data.remote.RestTimerUpsert
import com.example.timbertimer.data.remote.Session
import com.example.timbertimer.data.remote.SessionProjectUpdate
import com.example.timbertimer.data.remote.SupabaseApi
import com.example.timbertimer.data.remote.SupabaseAuth
import com.example.timbertimer.data.remote.isMissingSchema
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * The single source of truth for projects, records, to-dos and the shared timer
 * rows.
 *
 * Mirrors the web client's model: local-first, with the cloud taking over
 * entirely once an account is connected. Both clients read and write the same
 * five tables, so a tree planted on the phone is on the website when it loads.
 */
class TimberRepository(
    private val local: LocalStore,
    private val settings: SettingsStore,
    private val auth: SupabaseAuth,
    private val api: SupabaseApi,
    private val scope: CoroutineScope,
) {

    private val _records = MutableStateFlow<List<FocusRecord>>(emptyList())
    val records: StateFlow<List<FocusRecord>> = _records.asStateFlow()

    private val _projects = MutableStateFlow(ProjectBook.EMPTY)
    val projects: StateFlow<ProjectBook> = _projects.asStateFlow()

    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()

    private val _dataMode = MutableStateFlow(DataMode.LOCAL)
    val dataMode: StateFlow<DataMode> = _dataMode.asStateFlow()

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    /** True while the cloud is answering, so a failure is reported once, not on
     *  every resume. Opening the app on a train should not nag five times. */
    private var cloudReachable = true

    /**
     * Set once this database is found to genuinely predate the projects
     * migration — that is, once the server has said the table or the column is
     * not there.
     *
     * Never set for a request that simply failed. That distinction is the whole
     * point: latching on a lost packet would silently strip `project_id` from
     * every later write, and stop projects syncing at all, for the rest of the
     * session — with nothing on screen to say why.
     *
     * The app keeps working either way, so this is a quiet downgrade rather than
     * an error. Re-running `docs/supabase-schema.sql` turns it back on.
     */
    private var projectsTableMissing = false
    private var sessionProjectColumnMissing = false
    private var activeTimerProjectColumnMissing = false

    private val _projectsSyncBlocked = MutableStateFlow(false)

    /**
     * True when this account's projects cannot reach the cloud because the
     * database has not had the projects migration run against it.
     *
     * Surfaced so the app can say so, rather than leaving the user to wonder why
     * a project made on the laptop never arrives.
     */
    val projectsSyncBlocked: StateFlow<Boolean> = _projectsSyncBlocked.asStateFlow()

    private fun markProjectsTableMissing() {
        projectsTableMissing = true
        _projectsSyncBlocked.value = _session.value != null
    }

    /** Serialises whole-list note writes so two quick taps cannot interleave. */
    private val notesLock = Mutex()

    /** Serialises project writes, which read-modify-write the whole list. */
    private val projectsLock = Mutex()

    init {
        // Paint from disk first; the network can take its time.
        _projects.value = ProjectBook(local.readProjects().map(RecordMapper::toProject))
        _records.value = local.readSessions().map(RecordMapper::normalize).sortedByDescending { it.startedAt }
        _notes.value = applyStoredOrder(local.readNotes()).map(::toNote)
        scope.launch { reconcileProjects() }

        scope.launch {
            auth.sessionFlow.collect { session ->
                val previous = _session.value?.userId
                val changed = previous != session?.userId
                _session.value = session
                _dataMode.value = if (session != null) DataMode.CLOUD else DataMode.LOCAL
                if (changed) {
                    if (session == null) local.clearCloudCache()
                    // Signing in or out swaps the whole project list, so the id
                    // that was selected means nothing in the new one. Only an
                    // actual switch clears it — the first time an already-signed-
                    // in session is resolved at startup is not a switch, and
                    // must not throw away what the user last chose.
                    if (previous != null) settings.setSelectedProjectId("")
                    projectsTableMissing = false
                    sessionProjectColumnMissing = false
                    activeTimerProjectColumnMissing = false
                    _projectsSyncBlocked.value = false
                    refresh()
                }
            }
        }
    }

    // ---------- reading ----------

    /** Reloads everything from whichever store is currently authoritative. */
    suspend fun refresh() {
        loadProjects()
        loadRecords()
        reconcileProjects()
        loadNotes()
    }

    /**
     * Called when Realtime reports that something changed for this account.
     *
     * Deliberately a full reconcile rather than an application of the row delta:
     * one merge path is far easier to keep correct than two, and the delta path
     * would only be exercised when two devices are in use at once — exactly when
     * a bug would be hardest to reproduce.
     */
    fun onRemoteChange() {
        scope.launch { refresh() }
    }

    private suspend fun loadRecords() {
        val user = _session.value
        if (user == null) {
            _records.value = local.readSessions().map(RecordMapper::normalize)
                .sortedByDescending { it.startedAt }
            _dataMode.value = DataMode.LOCAL
            return
        }

        // Show the last known forest straight away. Waiting for the network to
        // answer before drawing anything is what makes an app feel broken on a
        // slow connection, and this list is almost always still right.
        showCached(user.userId)

        val token = auth.validAccessToken()
        if (token == null) {
            reportCloudUnreachable()
            return
        }

        // Anything held back while offline goes up before the fetch, so the list
        // that comes back already contains it.
        flushPending(token, user.userId)

        runCatching { api.fetchSessions(token, user.userId) }
            .onSuccess { rows ->
                local.writeCloudCache(user.userId, rows)
                _records.value = rows.map(RecordMapper::normalize).sortedByDescending { it.startedAt }
                _dataMode.value = DataMode.CLOUD
                cloudReachable = true
            }
            .onFailure { error ->
                reportCloudUnreachable()
                logSync(error)
            }
    }

    private fun reportCloudUnreachable() {
        if (!cloudReachable) return
        cloudReachable = false
        _messages.tryEmit(UiMessage.of(R.string.toast_cloud_load_fail))
    }

    /** Falls back to the last records seen in the cloud, plus anything pending. */
    private fun showCached(userId: String) {
        val cached = local.readCloudCache(userId) + local.readPending(userId)
        _records.value = cached
            .distinctBy { it.id }
            .map(RecordMapper::normalize)
            .sortedByDescending { it.startedAt }
        _dataMode.value = DataMode.CLOUD
    }

    private suspend fun flushPending(token: String, userId: String) {
        val pending = local.readPending(userId)
        if (pending.isEmpty()) return
        val rows = pending.map { RecordMapper.toInsert(it, userId) }
        val sent = runCatching { api.upsertSessions(token, rows, !sessionProjectColumnMissing) }
            .recoverCatching { error ->
                if (sessionProjectColumnMissing || !error.isMissingSchema()) throw error
                sessionProjectColumnMissing = true
                api.upsertSessions(token, rows, withProject = false)
            }
        sent.onSuccess { local.writePending(userId, emptyList()) }.onFailure { logSync(it) }
    }

    private suspend fun loadNotes() {
        val user = _session.value
        if (user == null) {
            _notes.value = applyStoredOrder(local.readNotes()).map(::toNote)
            return
        }

        val token = auth.validAccessToken() ?: run {
            _notes.value = applyStoredOrder(local.readCloudNotes(user.userId)).map(::toNote)
            return
        }

        runCatching { api.fetchNotes(token, user.userId) }
            .onSuccess { page ->
                local.writeCloudNotes(user.userId, page.rows)
                // The cloud order wins when the server could sort; otherwise this
                // device's own saved order is the better answer.
                val ordered = if (page.orderedRemotely) page.rows else applyStoredOrder(page.rows)
                _notes.value = ordered.map(::toNote)
            }
            .onFailure {
                _notes.value = applyStoredOrder(local.readCloudNotes(user.userId)).map(::toNote)
                logSync(it)
            }
    }

    /**
     * Re-reads the records while the app is open, so a session deleted or edited
     * on another device stops showing here.
     *
     * Deliberately quieter than [loadRecords]: no cache rewrite, no "cloud
     * unreachable" message and no state change unless the list actually
     * differs, because this runs on a timer rather than in response to the user.
     */
    suspend fun refreshRecordsFromCloud() {
        val user = _session.value ?: return
        val token = auth.validAccessToken() ?: return
        val rows = runCatching { api.fetchSessions(token, user.userId) }
            .getOrElse {
                logSync(it)
                return
            }

        val incoming = rows.map(RecordMapper::normalize).sortedByDescending { it.startedAt }
        if (incoming == _records.value) return
        local.writeCloudCache(user.userId, rows)
        _records.value = incoming
        reconcileProjects()
    }

    /** Polled while the app is open, so an edit made elsewhere lands here too. */
    suspend fun refreshNotesFromCloud() {
        val user = _session.value ?: return
        val token = auth.validAccessToken() ?: return
        val page = runCatching { api.fetchNotes(token, user.userId) }.getOrNull() ?: return
        val ordered = if (page.orderedRemotely) page.rows else applyStoredOrder(page.rows)
        val incoming = ordered.map(::toNote)
        if (incoming != _notes.value) {
            local.writeCloudNotes(user.userId, page.rows)
            _notes.value = incoming
        }
    }

    /**
     * Projects change rarely, so this only repaints when something actually
     * differs — a colour picked on the laptop lands here without a refresh.
     */
    suspend fun refreshProjectsFromCloud() {
        if (_session.value == null || projectsTableMissing) return
        loadProjects()
        reconcileProjects()
    }

    // ---------- projects ----------

    private suspend fun loadProjects() {
        val user = _session.value
        if (user == null) {
            _projects.value = ProjectBook(local.readProjects().map(RecordMapper::toProject))
            return
        }

        // Whatever was last seen for this account, so the forest keeps its
        // colours while the fetch is in flight.
        val cached = local.readCloudProjects(user.userId)
        if (cached.isNotEmpty()) _projects.value = ProjectBook(cached.map(RecordMapper::toProject))

        if (projectsTableMissing) return
        val token = auth.validAccessToken() ?: return

        runCatching { api.fetchProjects(token, user.userId) }
            .onSuccess { rows ->
                local.writeCloudProjects(user.userId, rows)
                _projects.value = ProjectBook(rows.map(RecordMapper::toProject))
            }
            .onFailure { error ->
                // The table only exists once the updated SQL has been run. Only
                // the server actually saying so counts — a failed request is
                // just a failed request, and the next one should try again.
                if (error.isMissingSchema()) markProjectsTableMissing()
                logSync(error)
                if (cached.isEmpty()) {
                    _projects.value = ProjectBook(local.readProjects().map(RecordMapper::toProject))
                }
            }
    }

    /**
     * Makes sure every project a record points at exists.
     *
     * Seeds the two built-ins on a fresh install, and rebuilds a project for
     * each distinct title used before projects existed — carrying the tree that
     * title already grew, so an existing forest looks unchanged. Ids and colours
     * are derived from the name, so two devices converge on the same result
     * without having to coordinate.
     */
    private suspend fun reconcileProjects() = projectsLock.withLock {
        val now = System.currentTimeMillis()
        val existing = _projects.value.all.associateBy { it.id }.toMutableMap()
        val created = mutableListOf<Project>()

        fun ensure(project: Project) {
            if (existing.containsKey(project.id)) return
            existing[project.id] = project
            created += project
        }

        ensure(Projects.builtIn(Projects.DEFAULT_ID, now))
        ensure(Projects.builtIn(Projects.REST_ID, now))

        _records.value.forEach { record ->
            val id = record.projectId
            if (!id.startsWith(Projects.LEGACY_PREFIX) || existing.containsKey(id)) return@forEach
            val name = record.title.trim().ifEmpty { Limits.DEFAULT_TITLE }
            ensure(
                Project(
                    id = id,
                    name = name.take(Projects.NAME_MAX),
                    color = Projects.colorForName(name),
                    tree = legacyTreeFor(name, record),
                    sortOrder = 100,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        }

        if (created.isEmpty()) return@withLock

        val merged = ProjectBook(existing.values.toList())
        _projects.value = merged
        persistProjects(merged.all)
        pushProjects(created)
    }

    /**
     * The species a migrated project should keep: an explicit per-name choice
     * made with the old tree picker first, then whatever that title last grew.
     */
    private fun legacyTreeFor(name: String, record: FocusRecord): String {
        TreeSpecies.byId(settings.legacyTreePreference(name))
            ?.takeIf { it != TreeSpecies.WILTED }
            ?.let { return it.id }
        TreeSpecies.byLabel(record.treeKind)
            ?.takeIf { it != TreeSpecies.WILTED }
            ?.let { return it.id }
        return Projects.treeForName(name)
    }

    /** Saves one project, creating it if the id is new. */
    suspend fun saveProject(project: Project) {
        projectsLock.withLock {
            val normalized = Projects.normalize(
                id = project.id,
                name = project.name,
                color = project.color,
                tree = project.tree,
                sortOrder = project.sortOrder,
                createdAt = project.createdAt,
                updatedAt = System.currentTimeMillis(),
            )
            val merged = _projects.value.all.filterNot { it.id == normalized.id } + normalized
            val book = ProjectBook(merged)
            _projects.value = book
            persistProjects(book.all)
            pushProjects(listOf(normalized))
        }
    }

    /**
     * Deletes a project and moves its records onto the default one, rather than
     * orphaning them — nothing should silently disappear from the history.
     */
    suspend fun deleteProject(id: String) {
        if (id in Projects.BUILTIN_IDS) return
        val target = Projects.DEFAULT_ID
        val affected = _records.value.filter { it.projectId == id }

        if (affected.isNotEmpty()) moveRecordsToProject(affected, target)

        projectsLock.withLock {
            val book = ProjectBook(_projects.value.all.filterNot { it.id == id })
            _projects.value = book
            persistProjects(book.all)

            val user = _session.value
            val token = if (user != null && !projectsTableMissing) auth.validAccessToken() else null
            if (user != null && token != null) {
                runCatching { api.deleteProject(token, user.userId, id) }.onFailure { logSync(it) }
            }
        }

        if (settings.selectedProjectId.value == id) settings.setSelectedProjectId(target)
    }

    private suspend fun moveRecordsToProject(affected: List<FocusRecord>, target: String) {
        val now = System.currentTimeMillis()
        val targetProject = _projects.value[target]
        val moved = affected.associate { record ->
            record.id to record.copy(
                projectId = target,
                treeKind = RecordMapper.pickTreeKind(targetProject, record.status),
                updatedAt = now,
            )
        }
        _records.value = _records.value.map { moved[it.id] ?: it }

        val user = _session.value
        if (user == null) {
            local.writeSessions(
                local.readSessions().map { row ->
                    moved[row.id]?.let { RecordMapper.toRow(it, null) } ?: row
                }
            )
            return
        }

        val token = auth.validAccessToken() ?: return
        if (sessionProjectColumnMissing) return
        runCatching {
            api.moveSessionsToProject(
                token,
                user.userId,
                affected.map { it.id },
                SessionProjectUpdate(projectId = target, updatedAt = Time.toIso(now)),
            )
        }.onFailure { error ->
            if (error.isMissingSchema()) sessionProjectColumnMissing = true
            logSync(error)
        }
        local.writeCloudCache(user.userId, _records.value.map { RecordMapper.toRow(it, user.userId) })
    }

    private fun persistProjects(projects: List<Project>) {
        val rows = projects.map(RecordMapper::toProjectRow)
        val user = _session.value
        if (user == null) local.writeProjects(rows) else local.writeCloudProjects(user.userId, rows)
    }

    private suspend fun pushProjects(projects: List<Project>) {
        if (projects.isEmpty() || projectsTableMissing) return
        val user = _session.value ?: return
        val token = auth.validAccessToken() ?: return
        runCatching {
            api.upsertProjects(token, projects.map { RecordMapper.toProjectUpsert(it, user.userId) })
        }.onFailure { error ->
            if (error.isMissingSchema()) markProjectsTableMissing()
            logSync(error)
            _messages.tryEmit(UiMessage.of(R.string.toast_cloud_projects_fail))
        }
    }

    /**
     * The project a task name belongs to: what was last chosen for it on this
     * device, then the project of the most recent session with that name.
     *
     * Returns null when the name is new, so the caller keeps whatever is already
     * selected rather than being thrown back to the default.
     */
    fun projectForTitle(title: String): String? {
        val key = title.trim().lowercase()
        if (key.isEmpty()) return null
        val book = _projects.value

        settings.projectForTask(key)?.takeIf { book.contains(it) }?.let { return it }

        return _records.value
            .filter { it.title.trim().lowercase() == key }
            .maxByOrNull { it.startedAt }
            ?.projectId
            ?.takeIf { book.contains(it) }
    }

    fun rememberTaskProject(title: String, projectId: String) {
        val key = title.trim().lowercase()
        if (key.isEmpty()) return
        settings.rememberTaskProject(key, projectId)
    }

    // ---------- records ----------

    /**
     * Saves a finished session.
     *
     * When signed in and the upload fails, the record is parked in the outbox
     * rather than dropped — a session the user actually sat through is the last
     * thing that should go missing because a tunnel ate the request.
     */
    suspend fun createRecord(record: FocusRecord): FocusRecord {
        rememberTaskProject(record.title, record.projectId)
        val user = _session.value
        val token = if (user != null) auth.validAccessToken() else null

        if (user != null) {
            // Park it in the outbox *before* going near the network. A session
            // the user actually sat through has to survive the process being
            // killed mid-upload, so it is durable from this line onward and the
            // upload is only ever an optimisation.
            local.writePending(user.userId, local.readPending(user.userId) + RecordMapper.toRow(record, user.userId))
            _records.value = (listOf(record) + _records.value).sortedByDescending { it.startedAt }

            if (token == null) {
                _messages.tryEmit(UiMessage.of(R.string.toast_offline))
                return record
            }

            val insert = RecordMapper.toInsert(record, user.userId)
            val result = runCatching { api.insertSession(token, insert, !sessionProjectColumnMissing) }
                .recoverCatching { error ->
                    // Only retry stripped-down when the server said the column is
                    // not there. Doing it for any failure would quietly drop the
                    // project from every record saved afterwards.
                    if (sessionProjectColumnMissing || !error.isMissingSchema()) throw error
                    sessionProjectColumnMissing = true
                    api.insertSession(token, insert, withProject = false)
                }

            result.onSuccess { row ->
                val saved = RecordMapper.normalize(row)
                local.writePending(user.userId, local.readPending(user.userId).filterNot { it.id == record.id })
                _records.value = (listOf(saved) + _records.value.filterNot { it.id == saved.id })
                    .sortedByDescending { it.startedAt }
                local.writeCloudCache(user.userId, _records.value.map { RecordMapper.toRow(it, user.userId) })
                return saved
            }
            logSync(result.exceptionOrNull())
            _messages.tryEmit(UiMessage.of(R.string.toast_cloud_save_fail))
            return record
        }

        val rows = listOf(RecordMapper.toRow(record, null)) +
            local.readSessions().filterNot { it.id == record.id }
        local.writeSessions(rows)
        _records.value = rows.map(RecordMapper::normalize).sortedByDescending { it.startedAt }
        reconcileProjects()
        return record
    }

    suspend fun updateRecord(record: FocusRecord) {
        val updated = record.copy(updatedAt = System.currentTimeMillis())
        rememberTaskProject(updated.title, updated.projectId)
        val user = _session.value

        if (user != null) {
            val token = auth.validAccessToken()
            if (token == null) {
                _messages.tryEmit(UiMessage.of(R.string.toast_cloud_update_fail))
                return
            }
            val payload = RecordMapper.toUpdate(updated)
            runCatching {
                api.updateSession(token, user.userId, updated.id, payload, !sessionProjectColumnMissing)
            }
                .recoverCatching { error ->
                    if (sessionProjectColumnMissing || !error.isMissingSchema()) throw error
                    sessionProjectColumnMissing = true
                    api.updateSession(token, user.userId, updated.id, payload, withProject = false)
                }
                .onSuccess { row ->
                    val saved = RecordMapper.normalize(row)
                    _records.value = _records.value
                        .map { if (it.id == saved.id) saved else it }
                        .sortedByDescending { it.startedAt }
                    local.writeCloudCache(user.userId, _records.value.map { RecordMapper.toRow(it, user.userId) })
                }
                .onFailure {
                    _messages.tryEmit(UiMessage.of(R.string.toast_cloud_update_fail))
                    logSync(it)
                }
            return
        }

        val rows = local.readSessions().map {
            if (it.id == updated.id) RecordMapper.toRow(updated, null) else it
        }
        local.writeSessions(rows)
        _records.value = rows.map(RecordMapper::normalize).sortedByDescending { it.startedAt }
    }

    suspend fun deleteRecord(record: FocusRecord) {
        val user = _session.value

        if (user != null) {
            val token = auth.validAccessToken()
            if (token == null) {
                _messages.tryEmit(UiMessage.of(R.string.toast_cloud_delete_fail))
                return
            }
            val result = runCatching { api.deleteSession(token, user.userId, record.id) }
            if (result.isFailure) {
                _messages.tryEmit(UiMessage.of(R.string.toast_cloud_delete_fail))
                logSync(result.exceptionOrNull())
                return
            }
            // A record still waiting to upload must also leave the outbox, or
            // the next refresh would put it straight back.
            local.writePending(user.userId, local.readPending(user.userId).filterNot { it.id == record.id })
            _records.value = _records.value.filterNot { it.id == record.id }
            local.writeCloudCache(user.userId, _records.value.map { RecordMapper.toRow(it, user.userId) })
            _messages.tryEmit(UiMessage.of(R.string.toast_record_deleted))
            return
        }

        local.writeSessions(local.readSessions().filterNot { it.id == record.id })
        _records.value = _records.value.filterNot { it.id == record.id }
        _messages.tryEmit(UiMessage.of(R.string.toast_record_deleted))
    }

    suspend fun deleteAllRecords() {
        val user = _session.value

        if (user != null) {
            val token = auth.validAccessToken()
            if (token == null) {
                _messages.tryEmit(UiMessage.of(R.string.toast_cloud_delete_all_fail))
                return
            }
            val result = runCatching { api.deleteAllSessions(token, user.userId) }
            if (result.isFailure) {
                _messages.tryEmit(UiMessage.of(R.string.toast_cloud_delete_all_fail))
                logSync(result.exceptionOrNull())
                return
            }
            local.writePending(user.userId, emptyList())
            local.writeCloudCache(user.userId, emptyList())
        } else {
            local.writeSessions(emptyList())
        }

        _records.value = emptyList()
        _messages.tryEmit(UiMessage.of(R.string.toast_all_deleted))
    }

    // ---------- to-do ----------

    suspend fun addNote(text: String) = notesLock.withLock {
        val trimmed = text.trim().take(Limits.NOTE_MAX)
        if (trimmed.isEmpty()) return@withLock
        val now = System.currentTimeMillis()
        val note = Note(UUID.randomUUID().toString(), trimmed, done = false, createdAt = now, updatedAt = now)
        _notes.value = listOf(note) + _notes.value
        persistNotes()
    }

    suspend fun toggleNote(id: String) = notesLock.withLock {
        val note = _notes.value.firstOrNull { it.id == id } ?: return@withLock
        val updated = note.copy(done = !note.done, updatedAt = System.currentTimeMillis())
        _notes.value = _notes.value.map { if (it.id == id) updated else it }

        val user = _session.value
        if (user != null) {
            val token = auth.validAccessToken()
            if (token != null) {
                runCatching {
                    api.updateNoteDone(
                        token, user.userId, id,
                        NoteDoneUpdate(updated.done, Time.toIso(updated.updatedAt)),
                    )
                }.onFailure { logSync(it) }
            }
            // Mirrored either way, so a tick made offline is still ticked after
            // a restart rather than quietly springing back.
            local.writeCloudNotes(user.userId, _notes.value.map(::toRow))
        } else {
            local.writeNotes(_notes.value.map(::toRow))
        }
    }

    suspend fun deleteNote(id: String) = notesLock.withLock {
        _notes.value = _notes.value.filterNot { it.id == id }

        val user = _session.value
        if (user != null) {
            val token = auth.validAccessToken()
            if (token != null) {
                runCatching { api.deleteNote(token, user.userId, id) }.onFailure { logSync(it) }
            }
            local.writeCloudNotes(user.userId, _notes.value.map(::toRow))
        } else {
            local.writeNotes(_notes.value.map(::toRow))
        }
        local.writeNotesOrder(_notes.value.map { it.id })
    }

    /**
     * Toggling from the home screen widget.
     *
     * The widget can be tapped in a process that was started for that tap alone,
     * before the account has been resolved and before any list has been loaded —
     * so both are made certain here rather than silently failing to find the id.
     */
    suspend fun toggleNoteFromWidget(id: String) {
        if (_session.value == null) {
            auth.currentSession()?.let { session ->
                _session.value = session
                _dataMode.value = DataMode.CLOUD
            }
        }
        if (_notes.value.none { it.id == id }) loadNotes()
        toggleNote(id)
    }

    /**
     * Adopts the order the user dragged the list into, and writes the whole list
     * back — the same thing the web client does when a drag ends.
     *
     * Takes ids rather than indices because the screen shows unfinished tasks
     * above finished ones, so a position on screen is not a position in the
     * stored list. Ids leave no room for that to be got wrong.
     */
    suspend fun reorderNotes(orderedIds: List<String>) = notesLock.withLock {
        val byId = _notes.value.associateBy { it.id }
        val moved = orderedIds.mapNotNull(byId::get)
        if (moved.isEmpty()) return@withLock

        // Anything the caller did not mention keeps its place at the end, so a
        // note added on another device mid-drag cannot be dropped by the write.
        val mentioned = orderedIds.toSet()
        val reordered = moved + _notes.value.filterNot { mentioned.contains(it.id) }
        if (reordered.map { it.id } == _notes.value.map { it.id }) return@withLock

        _notes.value = reordered
        persistNotes()
    }

    private suspend fun persistNotes() {
        val ordered = _notes.value
        local.writeNotesOrder(ordered.map { it.id })

        val user = _session.value
        if (user == null) {
            local.writeNotes(ordered.map(::toRow))
            return
        }

        val token = auth.validAccessToken()
        if (token != null) {
            val rows = ordered.mapIndexed { index, note ->
                NoteUpsert(
                    id = note.id,
                    userId = user.userId,
                    text = note.text,
                    done = note.done,
                    createdAt = Time.toIso(note.createdAt),
                    updatedAt = Time.toIso(note.updatedAt),
                    sortOrder = index,
                )
            }
            runCatching { api.upsertNotes(token, rows) }.onFailure { logSync(it) }
        }
        local.writeCloudNotes(user.userId, ordered.map(::toRow))
    }

    private fun applyStoredOrder(rows: List<NoteRow>): List<NoteRow> {
        val order = local.readNotesOrder()
        if (order.isEmpty()) return rows
        val byId = rows.associateBy { it.id }
        val ordered = order.mapNotNull(byId::get)
        val seen = order.toSet()
        return ordered + rows.filterNot { seen.contains(it.id) }
    }

    // ---------- the shared active-timer rows ----------

    /** Reports whether a timer is running for this account, per the shared row. */
    suspend fun fetchCloudTimer(): CloudTimer {
        val user = _session.value ?: return CloudTimer.Unavailable
        val token = auth.validAccessToken() ?: return CloudTimer.Unavailable

        val row = runCatching { api.fetchActiveTimer(token, user.userId) }
            .getOrElse {
                logSync(it)
                return CloudTimer.Unavailable
            }

        val startedAt = Time.parseIso(row?.startedAt) ?: return CloudTimer.None
        val endAt = Time.parseIso(row?.endAt) ?: (startedAt + (row?.durationSeconds ?: 0) * 1000L)
        return CloudTimer.Running(
            ActiveTimer(
                id = row?.timerId ?: UUID.randomUUID().toString(),
                mode = TimerMode.from(row?.mode),
                title = RecordMapper.cleanTitle(row?.title),
                // The project owns the species, so a timer started on another
                // device grows the same tree here.
                projectId = row?.projectId?.ifBlank { null } ?: Projects.DEFAULT_ID,
                durationMinutes = row?.durationMinutes ?: 0,
                durationSeconds = row?.durationSeconds ?: 0,
                startedAt = startedAt,
                endAt = endAt,
                cloudSynced = true,
            )
        )
    }

    suspend fun pushCloudTimer(timer: ActiveTimer): Boolean {
        val user = _session.value ?: return false
        val token = auth.validAccessToken() ?: return false
        val row = ActiveTimerUpsert(
            userId = user.userId,
            timerId = timer.id,
            mode = timer.mode.wire,
            title = RecordMapper.cleanTitle(timer.title),
            projectId = timer.projectId,
            durationMinutes = timer.durationMinutes,
            durationSeconds = timer.durationSeconds,
            startedAt = Time.toIso(timer.startedAt),
            endAt = Time.toIso(timer.endAt),
            updatedAt = Time.toIso(System.currentTimeMillis()),
        )
        return runCatching { api.upsertActiveTimer(token, row, !activeTimerProjectColumnMissing) }
            .recoverCatching { error ->
                // Older databases predate the column; the timer still syncs
                // without it, just without carrying its project across.
                if (activeTimerProjectColumnMissing || !error.isMissingSchema()) throw error
                activeTimerProjectColumnMissing = true
                api.upsertActiveTimer(token, row, withProject = false)
            }
            .onFailure { logSync(it) }
            .isSuccess
    }

    /**
     * Takes ownership of finishing a timer. Returns false only when the row was
     * already claimed elsewhere, which means another device recorded it.
     */
    suspend fun claimCloudTimer(timer: ActiveTimer): Boolean {
        if (!timer.cloudSynced) return true
        val user = _session.value ?: return true
        val token = auth.validAccessToken() ?: return true
        return runCatching { api.claimActiveTimer(token, user.userId, timer.id) }
            .getOrElse {
                logSync(it)
                // Unreachable is not the same as taken. Recording the session the
                // user just finished matters more than a rare duplicate row.
                true
            }
    }

    suspend fun clearCloudTimer() {
        val user = _session.value ?: return
        val token = auth.validAccessToken() ?: return
        runCatching { api.deleteActiveTimer(token, user.userId) }.onFailure { logSync(it) }
    }

    suspend fun fetchCloudRest(): CloudRest {
        val user = _session.value ?: return CloudRest.Unavailable
        val token = auth.validAccessToken() ?: return CloudRest.Unavailable
        val row = runCatching { api.fetchRestTimer(token, user.userId) }
            .getOrElse {
                logSync(it)
                return CloudRest.Unavailable
            }
        val startedAt = Time.parseIso(row?.startedAt) ?: return CloudRest.None
        return CloudRest.Running(startedAt)
    }

    suspend fun pushCloudRest(startedAt: Long) {
        val user = _session.value ?: return
        val token = auth.validAccessToken() ?: return
        runCatching {
            api.upsertRestTimer(
                token,
                RestTimerUpsert(
                    userId = user.userId,
                    startedAt = Time.toIso(startedAt),
                    updatedAt = Time.toIso(System.currentTimeMillis()),
                ),
            )
        }.onFailure { logSync(it) }
    }

    suspend fun clearCloudRest() {
        val user = _session.value ?: return
        val token = auth.validAccessToken() ?: return
        runCatching { api.deleteRestTimer(token, user.userId) }.onFailure { logSync(it) }
    }

    // ---------- derived ----------

    /** Minutes focused today, rests excluded — the same sum the Records screen shows. */
    fun todayFocusMinutes(): Int {
        val today = Time.localDateKey(System.currentTimeMillis())
        return _records.value
            .filter { it.status == RecordStatus.COMPLETED && !it.isRest }
            .filter { Time.localDateKey(if (it.endedAt > 0) it.endedAt else it.startedAt) == today }
            .sumOf { it.actualMinutes }
    }

    private fun toNote(row: NoteRow) = Note(
        id = row.id,
        text = row.text.take(Limits.NOTE_MAX),
        done = row.done,
        createdAt = Time.parseIso(row.createdAt) ?: System.currentTimeMillis(),
        updatedAt = Time.parseIso(row.updatedAt) ?: System.currentTimeMillis(),
    )

    private fun toRow(note: Note) = NoteRow(
        id = note.id,
        text = note.text,
        done = note.done,
        createdAt = Time.toIso(note.createdAt),
        updatedAt = Time.toIso(note.updatedAt),
    )

    private fun logSync(error: Throwable?) {
        if (error != null) android.util.Log.w("TimberRepository", "sync", error)
    }
}

/** What the shared `active_focus_timers` row says right now. */
sealed interface CloudTimer {
    /** Signed out, offline, or the request failed — the row's state is unknown. */
    data object Unavailable : CloudTimer

    /** Reached the table, and no timer is running for this account. */
    data object None : CloudTimer

    data class Running(val timer: ActiveTimer) : CloudTimer
}

/** The same three answers for the shared `active_rest_timers` row. */
sealed interface CloudRest {
    data object Unavailable : CloudRest

    data object None : CloudRest

    data class Running(val startedAt: Long) : CloudRest
}
