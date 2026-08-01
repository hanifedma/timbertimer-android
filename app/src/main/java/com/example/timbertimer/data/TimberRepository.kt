package com.example.timbertimer.data

import com.example.timbertimer.R
import com.example.timbertimer.core.Seed
import com.example.timbertimer.core.Time
import com.example.timbertimer.core.UiMessage
import com.example.timbertimer.data.local.LocalStore
import com.example.timbertimer.data.local.SettingsStore
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.DataMode
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.Note
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.data.model.TimerMode
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.data.remote.ActiveTimerUpsert
import com.example.timbertimer.data.remote.FocusSessionRow
import com.example.timbertimer.data.remote.NoteDoneUpdate
import com.example.timbertimer.data.remote.NoteRow
import com.example.timbertimer.data.remote.NoteUpsert
import com.example.timbertimer.data.remote.RestTimerUpsert
import com.example.timbertimer.data.remote.Session
import com.example.timbertimer.data.remote.SupabaseApi
import com.example.timbertimer.data.remote.SupabaseAuth
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
 * The single source of truth for records, to-dos and the shared timer rows.
 *
 * Mirrors the web client's model: local-first, with the cloud taking over
 * entirely once an account is connected. Both clients read and write the same
 * four tables, so a tree planted on the phone is on the website when it loads.
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

    /** Serialises whole-list note writes so two quick taps cannot interleave. */
    private val notesLock = Mutex()

    init {
        // Paint from disk first; the network can take its time.
        _records.value = local.readSessions().map(::normalize).sortedByDescending { it.startedAt }
        _notes.value = applyStoredOrder(local.readNotes()).map(::toNote)

        scope.launch {
            auth.sessionFlow.collect { session ->
                val changed = _session.value?.userId != session?.userId
                _session.value = session
                _dataMode.value = if (session != null) DataMode.CLOUD else DataMode.LOCAL
                if (changed) {
                    if (session == null) local.clearCloudCache()
                    refresh()
                }
            }
        }
    }

    // ---------- reading ----------

    /** Reloads everything from whichever store is currently authoritative. */
    suspend fun refresh() {
        loadRecords()
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
            _records.value = local.readSessions().map(::normalize).sortedByDescending { it.startedAt }
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
                _records.value = rows.map(::normalize).sortedByDescending { it.startedAt }
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
            .map(::normalize)
            .sortedByDescending { it.startedAt }
        _dataMode.value = DataMode.CLOUD
    }

    private suspend fun flushPending(token: String, userId: String) {
        val pending = local.readPending(userId)
        if (pending.isEmpty()) return
        runCatching { api.upsertSessions(token, pending.map { RecordMapper.toInsert(it, userId) }) }
            .onSuccess { local.writePending(userId, emptyList()) }
            .onFailure { logSync(it) }
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

    // ---------- records ----------

    /**
     * Saves a finished session.
     *
     * When signed in and the upload fails, the record is parked in the outbox
     * rather than dropped — a session the user actually sat through is the last
     * thing that should go missing because a tunnel ate the request.
     */
    suspend fun createRecord(record: FocusRecord): FocusRecord {
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

            val result = runCatching { api.insertSession(token, RecordMapper.toInsert(record, user.userId)) }
            result.onSuccess { row ->
                val saved = normalize(row)
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
        _records.value = rows.map(::normalize).sortedByDescending { it.startedAt }
        return record
    }

    suspend fun updateRecord(record: FocusRecord) {
        val updated = record.copy(updatedAt = System.currentTimeMillis())
        val user = _session.value

        if (user != null) {
            val token = auth.validAccessToken()
            if (token == null) {
                _messages.tryEmit(UiMessage.of(R.string.toast_cloud_update_fail))
                return
            }
            runCatching { api.updateSession(token, user.userId, updated.id, RecordMapper.toUpdate(updated)) }
                .onSuccess { row ->
                    val saved = normalize(row)
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
        _records.value = rows.map(::normalize).sortedByDescending { it.startedAt }
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

    /** Moves one item and writes the whole order back, as the web app does. */
    suspend fun moveNote(fromIndex: Int, toIndex: Int) = notesLock.withLock {
        val current = _notes.value.toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices) return@withLock
        current.add(toIndex, current.removeAt(fromIndex))
        _notes.value = current
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
                // The table has no species column, so the tree is derived from
                // the synced name. That lookup is deterministic and history is
                // shared, so the other device resolves the same one.
                speciesId = resolveSpeciesFor(row?.title).id,
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
        return runCatching {
            api.upsertActiveTimer(
                token,
                ActiveTimerUpsert(
                    userId = user.userId,
                    timerId = timer.id,
                    mode = timer.mode.wire,
                    title = RecordMapper.cleanTitle(timer.title),
                    durationMinutes = timer.durationMinutes,
                    durationSeconds = timer.durationSeconds,
                    startedAt = Time.toIso(timer.startedAt),
                    endAt = Time.toIso(timer.endAt),
                    updatedAt = Time.toIso(System.currentTimeMillis()),
                ),
            )
        }.onFailure { logSync(it) }.isSuccess
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

    // ---------- species ----------

    /**
     * The tree a session name should grow, in the web app's priority order:
     * an explicit choice saved on this device, then the species of the most
     * recent completed session with that name (which is synced, so it follows
     * the account across devices), then a stable per-name default.
     */
    fun resolveSpeciesFor(name: String?): TreeSpecies {
        val key = Seed.treeSeed(name)
        TreeSpecies.byId(settings.treePreference(key))
            ?.takeIf { it != TreeSpecies.WILTED }
            ?.let { return it }

        _records.value
            .asSequence()
            .filter { it.status == RecordStatus.COMPLETED && Seed.treeSeed(it.title) == key }
            .sortedByDescending { it.startedAt }
            .firstNotNullOfOrNull { TreeSpecies.byLabel(it.treeKind) }
            ?.takeIf { it != TreeSpecies.WILTED }
            ?.let { return it }

        return Seed.defaultSpeciesFor(name)
    }

    /** Minutes focused today, rests excluded — the same sum the Records screen shows. */
    fun todayFocusMinutes(): Int {
        val today = Time.localDateKey(System.currentTimeMillis())
        return _records.value
            .filter { it.status == RecordStatus.COMPLETED && !it.isRest }
            .filter { Time.localDateKey(if (it.endedAt > 0) it.endedAt else it.startedAt) == today }
            .sumOf { it.actualMinutes }
    }

    fun normalize(row: FocusSessionRow): FocusRecord =
        RecordMapper.normalize(row) { settings.treePreference(Seed.treeSeed(it)) }

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
