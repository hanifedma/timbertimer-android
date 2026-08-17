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
import com.example.timbertimer.data.local.StoredRest
import com.example.timbertimer.data.local.StoredTimer
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.RestTimer
import com.example.timbertimer.data.model.TimerMode
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
    private val restAlarm: RestAlarm,
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

    /**
     * Guards the rest's own completion, which has the same three racers as the
     * focus timer's. A plain flag is enough where [completing] needs a mutex,
     * because everything that sets it runs on the main dispatcher and the read
     * and the write are not separated by a suspension point.
     */
    private var restCompleting = false
    private var tickJob: Job? = null
    private var foreground = false
    private var lastCloudSyncAt = 0L
    private var lastListSyncAt = 0L

    /** The timer the run-in tone has already been played for, so it plays once. */
    private var finishSoonPlayedFor: String? = null

    init {
        _timer.value = local.readTimer()?.toActiveTimer()
        _rest.value = local.readRest()?.toRestTimer()
        if (_timer.value != null || _rest.value != null) ensureTicking()
    }

    // ---------- lifecycle ----------

    /** Polling only makes sense while someone is looking, or a timer is live. */
    fun setForeground(value: Boolean) {
        foreground = value
        if (value) {
            // Back in the app: the invitation has been accepted, or at least
            // seen — and so has any finished session, whose tree is now on
            // screen. Leaving either in the shade would be announcing news the
            // user is already looking at.
            notifications.cancelIdle()
            notifications.cancelCompleted()
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
        // A completing session deletes the shared row itself, and Realtime echoes
        // that back. Reconciling on the echo would race the record being written,
        // so the guard the poll already uses applies here too.
        if (completing) return
        scope.launch {
            if (completing) return@launch
            syncTimersFromCloud()
            ensureTicking()
        }
    }

    /** Used when the reminder is switched off while it is already showing. */
    fun clearIdleNudge() {
        notifications.cancelIdle()
    }

    /**
     * The exact alarm says a rest has run out.
     *
     * The process may have been dead until this moment, so the rest is read
     * back from disk rather than assumed to be in memory. Answering from the
     * clock alone is deliberate — see the note at the call site.
     */
    suspend fun onRestDue() {
        if (_rest.value == null) _rest.value = local.readRest()?.toRestTimer()
        completeRestIfDue()
    }

    /**
     * Puts back the ongoing notification after a swipe, rebuilt from live state.
     *
     * Rebuilt rather than reposted verbatim because time has passed: a countdown
     * dismissed at eight minutes left should come back saying seven. And it is
     * reposted only if it is still deserved — a timer that finished in the
     * meantime removes the reason for it, and the dismissal then stands.
     */
    fun restoreOngoingNotification() {
        if (!serviceWanted()) return
        // The service may itself have been the casualty; this both revives it
        // and reposts through it.
        syncService()
        postOngoing()
    }

    /**
     * The quarter-hourly heartbeat.
     *
     * Everything here is a no-op when the app is healthy, which is the point: it
     * costs one wake-up to find out, and the alternative is a phone whose
     * battery manager quietly stopped the service hours ago with nothing on
     * screen to say so.
     */
    fun onWatchdogTick() {
        if (!serviceWanted()) {
            alarms.cancelWatchdog()
            refreshIdleNudge()
            return
        }

        notifications.ensureChannels()
        // Restarting the service is the first thing done, and done synchronously:
        // the exact-alarm grant that permits it does not outlive this call.
        syncService()
        if (!notifications.isShowing(TimerNotifications.ID_RUNNING)) {
            postOngoing()
        }
    }

    /**
     * Leaves a quiet reminder in the shade when the app is closed with nothing
     * growing, and takes it away the moment something is.
     */
    private fun refreshIdleNudge() {
        val busy = _timer.value != null || _rest.value != null
        // With background sync on there is already a permanent notification
        // saying the same thing; a second one would just be clutter.
        val covered = settings.backgroundSync.value
        if (busy || foreground || covered || !settings.idleReminder.value) {
            notifications.cancelIdle()
            return
        }
        notifications.showIdle(idleSummary() ?: return)
    }

    /**
     * How long the forest has been still, and what grew today — null while
     * something is running, because then neither is what the notification is
     * for and gathering them would be work for nothing.
     */
    fun idleSummary(): TimerNotifications.IdleSummary? {
        if (_timer.value != null || _rest.value != null) return null
        return TimerNotifications.IdleSummary(
            lastEndedAt = repository.lastActivityEndedAt(),
            todayMinutes = repository.todayFocusMinutes(),
        )
    }

    /**
     * Reposts the ongoing notification, but only while something still wants
     * the service.
     *
     * Without that guard, finishing a session with background sync off posts it
     * again just after the stopping service took it down — leaving a permanent
     * notification in the shade with nothing running behind it and nothing left
     * that would ever clear it.
     */
    private fun postOngoing() {
        if (!serviceWanted()) return
        notifications.update(_timer.value, _rest.value, idleSummary())
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

            CloudRest.None -> {
                val saved = _rest.value
                when {
                    saved == null || repository.session.value == null -> Unit

                    // Started while signed out or with no signal. Publish it
                    // rather than lose it — and only call it published if the
                    // write actually landed, for the same reason the focus
                    // timer insists on that.
                    !saved.cloudSynced -> {
                        if (repository.pushCloudRest(saved)) markRestSynced()
                    }

                    // It was published, and it is gone: another device ended it.
                    else -> applyRest(null)
                }
            }

            is CloudRest.Running -> applyRest(cloud.toRestTimer(_rest.value))
        }
        // A rest whose moment passed while the app was closed — or while the
        // phone was off — is simply due now, and is answered here exactly once.
        completeRestIfDue()
    }

    /**
     * The shared row as a local rest.
     *
     * [current] is what covers the one case the row cannot: a database that
     * predates the rest-countdown columns returns every rest as open-ended, and
     * adopting that verbatim would silently disarm a countdown this device
     * started and is holding an alarm for. The row is authoritative about
     * *whether* a rest is running; this device's own copy is kept when the row
     * agrees it is the same rest and simply has less to say about it.
     */
    private fun CloudRest.Running.toRestTimer(current: RestTimer?): RestTimer {
        if (endAt == null && current?.endAt != null &&
            kotlin.math.abs(current.startedAt - startedAt) <= REST_MATCH_TOLERANCE_MS
        ) {
            return current
        }
        return RestTimer(
            startedAt = startedAt,
            endAt = endAt,
            durationMinutes = durationMinutes,
            // It came from the table, so it is by definition in it.
            cloudSynced = true,
        )
    }

    // ---------- starting and stopping ----------

    suspend fun start(title: String, mode: TimerMode, minutes: Int, projectId: String) {
        if (_timer.value != null) return

        val project = repository.projects.value[projectId]
        // An empty task name falls back to the project's, so picking a project
        // and pressing Start is enough.
        val cleanTitle = RecordMapper.cleanTitle(title.ifBlank { project.name })
        settings.setSessionName(cleanTitle)
        repository.rememberTaskProject(cleanTitle, project.id)
        finishSoonPlayedFor = null

        val stopwatch = mode == TimerMode.STOPWATCH
        val safeMinutes = if (stopwatch) 0 else minutes.coerceIn(1, Limits.TIMER_MINUTES_MAX)
        val startedAt = System.currentTimeMillis()
        // A stopwatch has no goal, so its end is parked 24h out — the same
        // convention the web app uses, and the table's own seconds ceiling.
        val durationSeconds = if (stopwatch) Limits.STOPWATCH_SECONDS else safeMinutes * 60

        val timer = ActiveTimer(
            id = UUID.randomUUID().toString(),
            mode = mode,
            title = cleanTitle,
            projectId = project.id,
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

    /**
     * The Finish button.
     *
     * A countdown that has not run out is not an abandoned session any more —
     * there is no such thing. It simply ran for as long as it ran.
     */
    suspend fun finish() = complete()

    private suspend fun completeIfDue() {
        val timer = _timer.value ?: return
        if (timer.isDue()) complete()
    }

    /**
     * Ends the session and records it.
     *
     * The claim comes first: with two signed-in devices both watching the same
     * countdown, the one that removes the shared row is the one that writes the
     * record. The other finds it already gone and quietly steps aside, so a
     * session is planted once rather than twice.
     */
    private suspend fun complete() = completionLock.withLock {
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

            val elapsedSeconds = timer.elapsedSeconds()
            // A countdown that reached its end is credited in full: a second of
            // rounding slack should not turn a 30-minute session into 29.
            // Anything else is the time that actually ran — at least a minute,
            // so a session leaves a tree rather than a record reading "0m", and
            // at most a day, which is what the table accepts.
            val ranOut = timer.mode == TimerMode.COUNTDOWN &&
                elapsedSeconds >= timer.durationSeconds - 1
            val rounded = (elapsedSeconds / 60.0).roundToLong().toInt()
            val actual = (if (ranOut) timer.durationMinutes else rounded)
                .coerceIn(1, Limits.MINUTES_MAX)
            val now = System.currentTimeMillis()

            val record = FocusRecord(
                id = UUID.randomUUID().toString(),
                title = timer.title,
                projectId = timer.projectId,
                actualMinutes = actual,
                startedAt = timer.startedAt,
                // The end is stored as exactly the minutes we keep, so the
                // calendar block and the "focused" figure can never disagree —
                // the same rule the record sheet and a calendar drag follow.
                endedAt = timer.startedAt + actual * 60_000L,
                treeKind = RecordMapper.pickTreeKind(repository.projects.value, timer.projectId),
                createdAt = now,
                updatedAt = now,
            )

            applyTimer(null)

            // The run-in tone is sized to end exactly at zero, so a session
            // finished during it has to be silenced before the chime lands.
            feedback.stop()
            feedback.playCompletion()
            feedback.vibrateCompletion()
            notifications.showCompleted(record)

            repository.createRecord(record)
            // applyTimer above cleared the notification's timer while this
            // record did not yet exist, so the count-up clock started from the
            // session before this one. Now that it is stored, restart it from
            // the right instant rather than leaving an hours-old figure sitting
            // under an alert that just said "planted".
            postOngoing()
            refreshIdleNudge()
            _messages.tryEmit(UiMessage.of(R.string.toast_session_planted))
        } finally {
            completing = false
        }
    }

    // ---------- rest ----------

    /**
     * Starts a rest.
     *
     * [minutes] of 0 is the open-ended stopwatch — the original behaviour, kept
     * because a rest you are willing to be interrupted from and a rest you want
     * to be pulled out of are genuinely different things. Anything else is a
     * countdown, and countdowns alarm.
     */
    suspend fun startRest(mode: TimerMode, minutes: Int) {
        if (_rest.value != null) return
        // An alarm still standing from the last rest would otherwise sit over
        // the new one, counting up from an instant that no longer means
        // anything. Starting another rest is an unambiguous acknowledgement.
        restAlarm.dismiss()

        val startedAt = System.currentTimeMillis()
        val countdown = mode == TimerMode.COUNTDOWN
        val safeMinutes = if (countdown) minutes.coerceIn(1, Limits.TIMER_MINUTES_MAX) else 0
        val rest = RestTimer(
            startedAt = startedAt,
            endAt = if (countdown) startedAt + safeMinutes * 60_000L else null,
            durationMinutes = safeMinutes,
        )

        applyRest(rest)
        ensureTicking()
        if (repository.pushCloudRest(rest)) markRestSynced()
    }

    /**
     * Adds [EXTEND_MINUTES] to a rest, from the alarm or from the panel.
     *
     * Deliberately measured from *now* rather than from the end that just
     * passed. The user is asking for another five minutes of rest starting when
     * they asked, and an extension that quietly expired thirty seconds later
     * because the alarm rang for four and a half would be worse than no button
     * at all.
     */
    suspend fun extendRest() {
        val rest = _rest.value ?: return
        if (!rest.isCountdown) return

        val now = System.currentTimeMillis()
        val base = maxOf(rest.endAt ?: now, now)
        val extended = rest.copy(
            endAt = base + EXTEND_MINUTES * 60_000L,
            durationMinutes = (rest.durationMinutes + EXTEND_MINUTES)
                .coerceAtMost(Limits.TIMER_MINUTES_MAX),
        )

        // Order matters: the alarm has to come down before the rest is re-armed,
        // or restoreOngoingNotification would find an alarm still ringing for a
        // rest that is now running again.
        restAlarm.dismiss()
        restCompleting = false
        applyRest(extended)
        ensureTicking()
        if (repository.pushCloudRest(extended)) markRestSynced()
        _messages.tryEmit(UiMessage.of(R.string.toast_rest_extended))
    }

    /**
     * "Five more minutes", from the alarm.
     *
     * A *new* rest rather than an extension of the old one, and it has to be:
     * by the time an alarm exists its rest has been recorded, its tree planted
     * and its shared row cleared, so there is nothing left to extend —
     * [extendRest] would find no rest and quietly do nothing. Un-planting the
     * tree to bolt five minutes onto the end would rewrite a record the user
     * can already see in their forest, and two short rests back to back is the
     * honest description of what actually happened anyway.
     */
    suspend fun restAgainFromAlarm() {
        restAlarm.dismiss()
        if (_rest.value != null) return
        startRest(TimerMode.COUNTDOWN, EXTEND_MINUTES)
        _messages.tryEmit(UiMessage.of(R.string.toast_rest_again))
    }

    /**
     * Ending a rest plants the wilted tree it grew, and its minutes count toward
     * the totals. Rests under a minute are dropped, so a stray tap leaves no litter.
     */
    suspend fun finishRest() {
        val rest = _rest.value ?: return
        // Finishing is an acknowledgement, whether it came from the panel, the
        // notification, or the countdown running out.
        restAlarm.dismiss()

        // A countdown that reached its end is credited in full, for the same
        // reason a focus session is: a second of rounding slack should not turn
        // a 15-minute rest into 14.
        val elapsedSeconds = rest.elapsedSeconds()
        val ranOut = rest.isCountdown && elapsedSeconds >= rest.durationMinutes * 60L - 1
        applyRest(null)
        repository.clearCloudRest()

        val minutes = if (ranOut) {
            rest.durationMinutes
        } else {
            (elapsedSeconds / 60.0).roundToLong().toInt()
        }
        if (minutes < 1) {
            _messages.tryEmit(UiMessage.of(R.string.toast_rest_discarded))
        } else {
            // Capped at a day for the same reason a focus session is: the table
            // refuses anything longer, and a rest left running is still a record.
            val capped = minutes.coerceAtMost(Limits.MINUTES_MAX)
            val now = System.currentTimeMillis()
            repository.createRecord(
                FocusRecord(
                    id = UUID.randomUUID().toString(),
                    title = Limits.REST_TITLE,
                    projectId = Projects.REST_ID,
                    actualMinutes = capped,
                    startedAt = rest.startedAt,
                    endedAt = rest.startedAt + capped * 60_000L,
                    // Rest is a project like any other, so it plants whatever
                    // tree that project grows — a wilted sprout unless it was
                    // changed.
                    treeKind = RecordMapper.pickTreeKind(repository.projects.value, Projects.REST_ID),
                    createdAt = now,
                    updatedAt = now,
                )
            )
            _messages.tryEmit(UiMessage.of(R.string.toast_rest_planted))
        }

        // For the same reason the focus path does it: applyRest above rebuilt
        // the notification while this record did not yet exist, so its count-up
        // clock started from whatever came before the rest. A rest resets that
        // clock, so say so now rather than a minute from now — and on the
        // discarded branch too, where the reset is the one that did not happen.
        postOngoing()
        refreshIdleNudge()
    }

    /**
     * A rest countdown has reached its end.
     *
     * The rest is recorded exactly as if the user had pressed Finish — the tree
     * is planted, the row is cleared, the minutes count — and *then* the alarm
     * goes up. That order is the important part: by the time anything is making
     * a noise the session is already durable, so an alarm that is ignored for
     * an hour, or a process killed while it rings, cannot cost the record.
     *
     * Guarded the same way [complete] is, because the same three things can
     * notice it: the ticker, the exact alarm, and a sync from another device.
     */
    private suspend fun completeRestIfDue() {
        val rest = _rest.value ?: return
        if (!rest.isDue()) return
        if (restCompleting) return
        restCompleting = true
        try {
            val minutes = rest.durationMinutes
            finishRest()
            restAlarm.fire(minutes)
        } finally {
            restCompleting = false
        }
    }

    // ---------- the ticker ----------

    private fun ensureTicking() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (
                isActive && (
                    _timer.value != null ||
                        _rest.value != null ||
                        foreground ||
                        settings.backgroundSync.value
                    )
            ) {
                _now.value = System.currentTimeMillis()

                val timer = _timer.value
                if (timer != null && !completing) {
                    if (timer.isDue()) {
                        complete()
                    } else {
                        maybePlayFinishSoon(timer)
                    }
                }

                completeRestIfDue()

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

        // Two axes. A live socket means a change arrives the moment it happens, so
        // the poll becomes a safety net for a socket that died without saying so.
        // Being in the background means nobody is watching this screen — but the
        // widget still is, so the lists cannot stop refreshing entirely, only
        // slow down.
        val interval = when {
            liveSync.value && !foreground -> BACKGROUND_LIVE_POLL_MS
            liveSync.value -> LIVE_POLL_MS
            !foreground -> BACKGROUND_POLL_MS
            else -> CLOUD_POLL_MS
        }

        if (now - lastCloudSyncAt >= interval) {
            lastCloudSyncAt = now
            syncTimersFromCloud()
        }
        if (now - lastListSyncAt >= interval) {
            lastListSyncAt = now
            repository.refreshNotesFromCloud()

            // Records need the same safety net the notes already had. Realtime
            // does not always cover this one: on an RLS-protected table a DELETE
            // carries only the primary key unless REPLICA IDENTITY is FULL, so
            // neither the user_id filter nor the policy can be evaluated and the
            // event is dropped. Without this poll, a session deleted on another
            // device stayed on screen until the app was reopened.
            repository.refreshRecordsFromCloud()
            repository.refreshProjectsFromCloud()
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

            // Only a rest we had published can be "the one that vanished"; a
            // local-only rest simply has not been pushed yet, and hydrate is
            // where that is put right.
            CloudRest.None -> if (_rest.value?.cloudSynced == true) applyRest(null)

            is CloudRest.Running -> {
                val current = _rest.value
                val adopted = cloud.toRestTimer(current)
                // The end matters as well as the start now: extending a rest on
                // one device has to move the other device's alarm, and that
                // leaves the start exactly where it was.
                if (current == null ||
                    kotlin.math.abs(current.startedAt - adopted.startedAt) > REST_MATCH_TOLERANCE_MS ||
                    current.endAt != adopted.endAt
                ) {
                    applyRest(adopted)
                }
                completeRestIfDue()
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
        local.writeRest(rest?.toStored())
        // The same backstop the focus countdown gets, and for the same reason:
        // the ticker only notices a rest is due while the process is alive, and
        // a vendor battery manager can end that at any moment. The alarm fires
        // regardless and both paths funnel into one guarded completion.
        if (rest?.endAt != null) {
            alarms.scheduleRest(rest.endAt)
        } else {
            alarms.cancelRest()
        }
        syncService()
        refreshIdleNudge()
    }

    private fun markSynced() {
        val timer = _timer.value ?: return
        val synced = timer.copy(cloudSynced = true)
        _timer.value = synced
        local.writeTimer(synced.toStored())
    }

    /**
     * Records that the rest reached the table.
     *
     * Written straight through rather than via [applyRest]: nothing about the
     * rest itself changed, so re-arming the alarm and rebuilding the
     * notification would be work for a flag the user cannot see.
     */
    private fun markRestSynced() {
        val rest = _rest.value ?: return
        if (rest.cloudSynced) return
        val synced = rest.copy(cloudSynced = true)
        _rest.value = synced
        local.writeRest(synced.toStored())
    }

    /**
     * The service has two reasons to be alive: a running timer, and background
     * sync. Without the second, the process only exists while a timer runs or a
     * screen is open — which is why a task ticked on the phone could sit
     * unnoticed in the tablet's widget until the app was opened there.
     */
    private fun syncService() {
        if (serviceWanted()) {
            TimerService.start(appContext)
            // Re-armed on every pass, so the heartbeat keeps beating for as long
            // as there is something to watch and stops the moment there is not.
            alarms.scheduleWatchdog(urgent = _timer.value != null || _rest.value != null)
        } else {
            TimerService.stop(appContext)
            alarms.cancelWatchdog()
        }
    }

    /**
     * The reasons the service — and its notification — should be alive.
     *
     * A ringing rest alarm is one of them, and the least obvious: the rest it
     * belongs to has already been recorded and cleared by the time it rings, so
     * without this the process would become killable at the exact moment it is
     * supposed to be making a noise. The looping track and the repeating
     * waveform both belong to this process and stop when it does.
     */
    private fun serviceWanted(): Boolean =
        _timer.value != null || _rest.value != null ||
            restAlarm.isRinging || settings.backgroundSync.value

    /** Called when the background-sync switch is flipped, to start or stop now. */
    fun onBackgroundSyncChanged() {
        syncService()
        if (settings.backgroundSync.value) ensureTicking() else refreshIdleNudge()
    }

    private fun RestTimer.toStored() = StoredRest(
        startedAt = startedAt,
        endAt = endAt,
        durationMinutes = durationMinutes,
        cloudSynced = cloudSynced,
    )

    private fun StoredRest.toRestTimer() = RestTimer(
        startedAt = startedAt,
        endAt = endAt,
        durationMinutes = durationMinutes,
        cloudSynced = cloudSynced,
    )

    private fun ActiveTimer.toStored() = StoredTimer(
        id = id,
        mode = mode.wire,
        title = title,
        projectId = projectId,
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
        projectId = projectId?.ifBlank { null } ?: Projects.DEFAULT_ID,
        durationMinutes = durationMinutes,
        durationSeconds = durationSeconds,
        startedAt = startedAt,
        endAt = endAt,
        cloudSynced = cloudSynced,
    )

    private companion object {
        const val TICK_MS = 1000L

        /** What the alarm's and the panel's "+5 min" adds. */
        const val EXTEND_MINUTES = 5

        /**
         * How far apart two rests' starts may be and still be the same rest.
         * Clock skew between two devices is the thing being tolerated.
         */
        const val REST_MATCH_TOLERANCE_MS = 2000L

        /** The web client's polling interval, used when Realtime is unavailable. */
        const val CLOUD_POLL_MS = 15_000L

        /** Backstop interval while the live socket is connected. */
        const val LIVE_POLL_MS = 60_000L

        /** No screen open, no socket: still keep the widget honest. */
        const val BACKGROUND_POLL_MS = 60_000L

        /** No screen open and a live socket: purely a safety net. */
        const val BACKGROUND_LIVE_POLL_MS = 300_000L
    }
}
