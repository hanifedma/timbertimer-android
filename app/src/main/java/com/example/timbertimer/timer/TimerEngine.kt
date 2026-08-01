package com.example.timbertimer.timer

import android.content.Context
import com.example.timbertimer.R
import com.example.timbertimer.core.UiMessage
import com.example.timbertimer.data.CloudRest
import com.example.timbertimer.data.CloudTimer
import com.example.timbertimer.data.RecordMapper
import com.example.timbertimer.data.TimberRepository
import com.example.timbertimer.data.local.LocalStore
import com.example.timbertimer.data.local.SettingsStore
import com.example.timbertimer.data.local.StoredTimer
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.data.model.RestTimer
import com.example.timbertimer.data.model.TimerMode
import com.example.timbertimer.data.model.TreeSpecies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Owns the running focus timer and the rest stopwatch.
 *
 * Nothing here counts down. The timer is an instant to finish at, and every
 * reading is derived from the wall clock — so being killed, dozed, or rebooted
 * cannot make it drift, and there is no state to repair on the way back. The
 * ticker exists only to notice that the moment has arrived and to move the UI.
 *
 * It lives at application scope rather than in a ViewModel, because a session
 * has to finish and be recorded whether or not any screen is watching.
 */
class TimerEngine(
    context: Context,
    private val local: LocalStore,
    private val settings: SettingsStore,
    private val repository: TimberRepository,
    private val feedback: TimerFeedback,
    private val notifications: TimerNotifications,
    private val alarms: TimerAlarms,
    private val liveSync: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    private val _timer = MutableStateFlow<ActiveTimer?>(null)
    val timer: StateFlow<ActiveTimer?> = _timer.asStateFlow()

    private val _rest = MutableStateFlow<RestTimer?>(null)
    val rest: StateFlow<RestTimer?> = _rest.asStateFlow()

    /** Pulses once a second so anything showing a clock repaints. */
    private val _now = MutableStateFlow(System.currentTimeMillis())
    val now: StateFlow<Long> = _now.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    private val completionLock = Mutex()
    private var completing = false
    private var tickJob: Job? = null
    private var foreground = false
    private var lastCloudSyncAt = 0L
    private var lastNotesSyncAt = 0L

    /** The timer the run-in tone has already been played for, so it plays once. */
    private var finishSoonPlayedFor: String? = null

    init {
        _timer.value = local.readTimer()?.toActiveTimer()
        _rest.value = local.readRestStartedAt()?.let(::RestTimer)
        if (_timer.value != null || _rest.value != null) ensureTicking()
    }

    // ---------- lifecycle ----------

    /** Polling only makes sense while someone is looking, or a timer is live. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            // Back in the app: the invitation has been accepted, or at least seen.
            notifications.cancelIdle()
            ensureTicking()
        } else {
            refreshIdleNudge()
        }
    }

    /**
     * Realtime says something changed for this account — most importantly, that
     * a timer was started or finished on another device. Reconcile now rather
     * than waiting for the next poll.
     */
    fun onRemoteChange() {
        scope.launch {
            syncTimersFromCloud()
            ensureTicking()
        }
    }

    /** Used when the reminder is switched off while it is already showing. */
    fun clearIdleNudge() {
        notifications.cancelIdle()
    }

    /**
     * Leaves a quiet reminder in the shade when the app is closed with nothing
     * growing, and takes it away the moment something is.
     */
    private fun refreshIdleNudge() {
        val busy = _timer.value != null || _rest.value != null
        if (busy || foreground || !settings.idleReminder.value) {
            notifications.cancelIdle()
            return
        }
        notifications.showIdle(repository.todayFocusMinutes())
    }

    /**
     * Reconciles this device against the account's shared timer row.
     *
     * Whoever the row belongs to is authoritative: a countdown started on the
     * laptop shows up here mid-flight, and one this device started while signed
     * out is published on the way in. A timer whose moment passed while the app
     * was closed finishes now, exactly once.
     */
    suspend fun hydrate() {
        val saved = _timer.value ?: local.readTimer()?.toActiveTimer()

        when (val cloud = repository.fetchCloudTimer()) {
            CloudTimer.Unavailable -> {
                // Signed out or unreachable — this device's own copy stands.
                applyTimer(saved)
            }

            CloudTimer.None -> {
                if (saved != null && !saved.cloudSynced) {
                    // Started while signed out; publish it rather than lose it.
                    // Only mark it published if the write actually landed — a
                    // timer wrongly believed to be in the table would find
                    // nothing to claim when it finishes, and conclude another
                    // device had recorded it. The session would vanish.
                    applyTimer(saved)
                    if (repository.pushCloudTimer(saved)) markSynced()
                } else {
                    // It was published, and it is gone: another device finished it.
                    applyTimer(null)
                }
            }

            is CloudTimer.Running -> applyTimer(cloud.timer)
        }

        completeIfDue()
        hydrateRest()
        ensureTicking()
    }

    private suspend fun hydrateRest() {
        when (val cloud = repository.fetchCloudRest()) {
            CloudRest.Unavailable -> Unit
            CloudRest.None -> if (_rest.value != null && repository.session.value != null) {
                applyRest(null)
            }

            is CloudRest.Running -> applyRest(RestTimer(cloud.startedAt))
        }
    }

    // ---------- starting and stopping ----------

    suspend fun start(title: String, mode: TimerMode, minutes: Int, speciesId: String?) {
        if (_timer.value != null) return

        val cleanTitle = RecordMapper.cleanTitle(title)
        settings.setSessionName(cleanTitle)
        finishSoonPlayedFor = null

        val stopwatch = mode == TimerMode.STOPWATCH
        val safeMinutes = if (stopwatch) 0 else minutes.coerceIn(1, Limits.MINUTES_MAX)
        val startedAt = System.currentTimeMillis()
        // A stopwatch has no goal, so its end is parked 24h out — the same
        // convention the web app uses, and the table's own seconds ceiling.
        val durationSeconds = if (stopwatch) Limits.STOPWATCH_SECONDS else safeMinutes * 60

        val timer = ActiveTimer(
            id = UUID.randomUUID().toString(),
            mode = mode,
            title = cleanTitle,
            speciesId = speciesId,
            durationMinutes = safeMinutes,
            durationSeconds = if (stopwatch) 0 else durationSeconds,
            startedAt = startedAt,
            endAt = startedAt + durationSeconds * 1000L,
            cloudSynced = false,
        )

        applyTimer(timer)
        ensureTicking()
        _messages.tryEmit(
            UiMessage.of(if (stopwatch) R.string.toast_stopwatch_started else R.string.toast_session_started)
        )

        if (repository.pushCloudTimer(timer)) markSynced()
    }

    /** The Finish button: a countdown that has not run out is an abandoned one. */
    suspend fun finish() {
        val timer = _timer.value ?: return
        val status = when {
            timer.mode == TimerMode.STOPWATCH -> RecordStatus.COMPLETED
            timer.remainingSeconds() <= 0L -> RecordStatus.COMPLETED
            else -> RecordStatus.ABANDONED
        }
        complete(status)
    }

    suspend fun giveUp() {
        if (_timer.value == null) return
        complete(RecordStatus.ABANDONED)
    }

    private suspend fun completeIfDue() {
        val timer = _timer.value ?: return
        if (timer.isDue()) complete(RecordStatus.COMPLETED)
    }

    /**
     * Ends the session and records it.
     *
     * The claim comes first: with two signed-in devices both watching the same
     * countdown, the one that removes the shared row is the one that writes the
     * record. The other finds it already gone and quietly steps aside, so a
     * session is planted once rather than twice.
     */
    private suspend fun complete(status: RecordStatus) = completionLock.withLock {
        val timer = _timer.value ?: return@withLock
        if (completing) return@withLock
        completing = true
        try {
            if (!repository.claimCloudTimer(timer)) {
                feedback.stop()
                applyTimer(null)
                repository.refresh()
                _messages.tryEmit(UiMessage.of(R.string.toast_timer_finished_elsewhere))
                return@withLock
            }

            val stopwatch = timer.mode == TimerMode.STOPWATCH
            val elapsedSeconds = timer.elapsedSeconds()
            val roundedMinutes = (elapsedSeconds / 60.0).roundToLong().toInt()
            val actual = if (status == RecordStatus.COMPLETED) max(1, roundedMinutes)
            else max(0, roundedMinutes)
            val endedAt = System.currentTimeMillis()

            val record = FocusRecord(
                id = UUID.randomUUID().toString(),
                title = timer.title,
                durationMinutes = if (stopwatch) max(1, actual) else timer.durationMinutes,
                actualMinutes = when {
                    stopwatch -> actual
                    // Reaching the goal is credited in full: a second of rounding
                    // slack should not turn a 25-minute session into 24.
                    status == RecordStatus.COMPLETED &&
                        elapsedSeconds >= timer.durationSeconds - 1 -> timer.durationMinutes

                    else -> actual
                },
                status = status,
                startedAt = timer.startedAt,
                endedAt = endedAt,
                treeKind = RecordMapper.pickTreeKind(timer.title, status, timer.speciesId) {
                    settings.treePreference(it)
                },
                createdAt = endedAt,
                updatedAt = endedAt,
            )

            applyTimer(null)

            if (status == RecordStatus.COMPLETED) {
                feedback.playCompletion()
                feedback.vibrateCompletion()
                notifications.showCompleted(record)
            } else {
                feedback.stop()
            }

            repository.createRecord(record)
            _messages.tryEmit(
                UiMessage.of(
                    if (status == RecordStatus.COMPLETED) R.string.toast_session_planted
                    else R.string.toast_session_abandoned
                )
            )
        } finally {
            completing = false
        }
    }

    // ---------- rest ----------

    suspend fun startRest() {
        if (_rest.value != null) return
        val startedAt = System.currentTimeMillis()
        applyRest(RestTimer(startedAt))
        ensureTicking()
        repository.pushCloudRest(startedAt)
    }

    /**
     * Ending a rest plants the wilted tree it grew, and its minutes count toward
     * the totals. Rests under a minute are dropped, so a stray tap leaves no litter.
     */
    suspend fun finishRest() {
        val rest = _rest.value ?: return
        val elapsedSeconds = rest.elapsedSeconds()
        applyRest(null)
        repository.clearCloudRest()

        val minutes = (elapsedSeconds / 60.0).roundToLong().toInt()
        if (minutes < 1) {
            _messages.tryEmit(UiMessage.of(R.string.toast_rest_discarded))
            return
        }

        val endedAt = System.currentTimeMillis()
        repository.createRecord(
            FocusRecord(
                id = UUID.randomUUID().toString(),
                title = Limits.REST_TITLE,
                durationMinutes = minutes,
                actualMinutes = minutes,
                status = RecordStatus.COMPLETED,
                startedAt = rest.startedAt,
                endedAt = endedAt,
                treeKind = TreeSpecies.WILTED.label,
                createdAt = endedAt,
                updatedAt = endedAt,
            )
        )
        _messages.tryEmit(UiMessage.of(R.string.toast_rest_planted))
    }

    // ---------- the ticker ----------

    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive && (_timer.value != null || _rest.value != null || foreground)) {
                _now.value = System.currentTimeMillis()

                val timer = _timer.value
                if (timer != null && !completing) {
                    if (timer.isDue()) {
                        complete(RecordStatus.COMPLETED)
                    } else {
                        maybePlayFinishSoon(timer)
                    }
                }

                pollCloud()
                delay(TICK_MS)
            }
            _now.value = System.currentTimeMillis()
        }
    }

    private fun maybePlayFinishSoon(timer: ActiveTimer) {
        if (timer.mode != TimerMode.COUNTDOWN) return
        if (finishSoonPlayedFor == timer.id) return
        val remaining = timer.remainingSeconds()
        if (remaining !in 1..10) return
        finishSoonPlayedFor = timer.id
        feedback.playFinishSoon(remaining)
    }

    private suspend fun pollCloud() {
        if (repository.session.value == null || completing) return
        val now = System.currentTimeMillis()

        // While the socket is up, changes arrive the moment they happen, so the
        // poll drops back to being a safety net for a socket that silently died.
        val interval = if (liveSync.value) LIVE_POLL_MS else CLOUD_POLL_MS

        if (now - lastCloudSyncAt >= interval) {
            lastCloudSyncAt = now
            syncTimersFromCloud()
        }
        if (foreground && now - lastNotesSyncAt >= interval) {
            lastNotesSyncAt = now
            repository.refreshNotesFromCloud()
        }
    }

    /** Adopts whatever the account's shared rows say, in either direction. */
    private suspend fun syncTimersFromCloud() {
        when (val cloud = repository.fetchCloudTimer()) {
            CloudTimer.Unavailable -> Unit

            CloudTimer.None ->
                // Only a timer we had published can be "the one that vanished";
                // a local-only timer just has not been pushed yet.
                if (_timer.value?.cloudSynced == true) {
                    applyTimer(null)
                    repository.refresh()
                }

            is CloudTimer.Running -> {
                val current = _timer.value
                if (current == null || current.id != cloud.timer.id ||
                    current.startedAt != cloud.timer.startedAt
                ) {
                    applyTimer(cloud.timer)
                }
                completeIfDue()
            }
        }

        when (val cloud = repository.fetchCloudRest()) {
            CloudRest.Unavailable -> Unit
            CloudRest.None -> if (_rest.value != null) applyRest(null)
            is CloudRest.Running -> {
                val current = _rest.value
                if (current == null || kotlin.math.abs(current.startedAt - cloud.startedAt) > 2000) {
                    applyRest(RestTimer(cloud.startedAt))
                }
            }
        }
    }

    // ---------- state plumbing ----------

    private fun applyTimer(timer: ActiveTimer?) {
        _timer.value = timer
        local.writeTimer(timer?.toStored())
        if (timer != null && timer.mode == TimerMode.COUNTDOWN) {
            alarms.schedule(timer.endAt)
        } else {
            alarms.cancel()
        }
        if (timer == null) finishSoonPlayedFor = null
        syncService()
        refreshIdleNudge()
    }

    private fun applyRest(rest: RestTimer?) {
        _rest.value = rest
        local.writeRestStartedAt(rest?.startedAt)
        syncService()
        refreshIdleNudge()
    }

    private fun markSynced() {
        val timer = _timer.value ?: return
        val synced = timer.copy(cloudSynced = true)
        _timer.value = synced
        local.writeTimer(synced.toStored())
    }

    private fun syncService() {
        if (_timer.value != null || _rest.value != null) {
            TimerService.start(appContext)
        } else {
            TimerService.stop(appContext)
        }
    }

    private fun ActiveTimer.toStored() = StoredTimer(
        id = id,
        mode = mode.wire,
        title = title,
        speciesId = speciesId,
        durationMinutes = durationMinutes,
        durationSeconds = durationSeconds,
        startedAt = startedAt,
        endAt = endAt,
        cloudSynced = cloudSynced,
    )

    private fun StoredTimer.toActiveTimer() = ActiveTimer(
        id = id,
        mode = TimerMode.from(mode),
        title = RecordMapper.cleanTitle(title),
        speciesId = speciesId,
        durationMinutes = durationMinutes,
        durationSeconds = durationSeconds,
        startedAt = startedAt,
        endAt = endAt,
        cloudSynced = cloudSynced,
    )

    private companion object {
        const val TICK_MS = 1000L

        /** The web client's polling interval, used when Realtime is unavailable. */
        const val CLOUD_POLL_MS = 15_000L

        /** Backstop interval while the live socket is connected. */
        const val LIVE_POLL_MS = 60_000L
    }
}
