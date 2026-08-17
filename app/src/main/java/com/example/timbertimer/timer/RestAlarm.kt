package com.example.timbertimer.timer

import android.content.Context
import android.os.PowerManager
import com.example.timbertimer.data.local.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The alarm that ends a rest, and refuses to be missed.
 *
 * A focus session finishing is *news*: the tree is planted either way, and an
 * alert that is slept through costs nothing. A rest finishing is an
 * **instruction** — the user asked to be pulled out of something enjoyable at a
 * particular moment, and an alert that is slept through is the one failure that
 * makes the whole feature pointless. Everything here follows from that
 * difference.
 *
 * Five things have to go right, and each is defeated by a different part of the
 * phone:
 *
 * | Against | What answers it |
 * |---|---|
 * | the process being killed | an exact alarm re-starts it ([TimerAlarms]) |
 * | Doze / battery saver | `setExactAndAllowWhileIdle`, and a wake lock once ringing |
 * | silent mode / ringer off | `USAGE_ALARM` on both the sound and the vibration |
 * | Do Not Disturb | `CATEGORY_ALARM`, plus channel DND bypass where granted |
 * | a locked, dark screen | a full-screen intent that turns the screen on |
 *
 * ### Why it stops
 *
 * It rings for [RING_MS] and then falls quiet, leaving a notification that
 * cannot be swiped away. An alarm with no end is not more reliable, only more
 * dangerous: the common case for one is a phone left on a desk while its owner
 * is in a meeting two rooms away, and a device that screams for an hour teaches
 * people to turn the feature off. Two minutes is longer than any alarm clock's
 * default snooze prompt and long enough to cross a room for; what survives past
 * it is the notification, which is what actually carries the message.
 *
 * Nothing here can run away with the device. Both the looping track and the
 * repeating waveform are owned by this process, so if the process dies they
 * stop with it — the failure mode is silence, never a phone that will not shut
 * up.
 */
class RestAlarm(
    context: Context,
    private val settings: SettingsStore,
    private val feedback: TimerFeedback,
    private val notifications: TimerNotifications,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext

    private val _ringing = MutableStateFlow<Ringing?>(null)

    /**
     * The alarm as the UI needs it, so an open app shows its own full-bleed
     * sheet rather than relying on a notification the user is already looking
     * past.
     */
    val ringing: StateFlow<Ringing?> = _ringing.asStateFlow()

    private var ringJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * What is being announced.
     *
     * [loud] goes false at the cap while the alarm itself stays up, which is
     * what lets the sheet stop offering to silence something that is already
     * silent.
     */
    data class Ringing(
        val durationMinutes: Int,
        val firedAt: Long,
        val loud: Boolean = true,
    )

    val isRinging: Boolean get() = _ringing.value != null

    /**
     * Starts ringing for a rest of [durationMinutes].
     *
     * Idempotent: the three things that can notice a rest is due — the ticker,
     * the exact alarm, and a sync from another device — all land here, and only
     * the first of them should be heard.
     */
    fun fire(durationMinutes: Int) {
        if (_ringing.value != null) return

        _ringing.value = Ringing(durationMinutes = durationMinutes, firedAt = System.currentTimeMillis())

        // Before the noise, not after: the screen coming on is the fastest of
        // these to be noticed, and on a locked phone it is the only one that
        // does not depend on a channel setting going our way.
        acquireWakeLock()
        notifications.showRestAlarm(durationMinutes, loud = true)
        feedback.startRestAlarm()

        ringJob?.cancel()
        ringJob = scope.launch {
            delay(RING_MS)
            // Quieten, but do not clear. The message outlives the noise.
            feedback.stopRestAlarm()
            releaseWakeLock()
            val current = _ringing.value ?: return@launch
            _ringing.value = current.copy(loud = false)
            notifications.showRestAlarm(current.durationMinutes, loud = false)
        }
    }

    /**
     * The user has seen it. Stops everything and takes the notification away.
     *
     * This is the only thing that clears the alarm — a swipe does not, which is
     * the whole of what "stubborn" means here.
     */
    fun dismiss() {
        if (_ringing.value == null) return
        ringJob?.cancel()
        ringJob = null
        _ringing.value = null
        feedback.stopRestAlarm()
        notifications.cancelRestAlarm()
        releaseWakeLock()
    }

    /** Silences the noise but leaves the alarm standing, for the sheet's button. */
    fun silence() {
        val current = _ringing.value ?: return
        if (!current.loud) return
        ringJob?.cancel()
        ringJob = null
        feedback.stopRestAlarm()
        releaseWakeLock()
        _ringing.value = current.copy(loud = false)
        notifications.showRestAlarm(current.durationMinutes, loud = false)
    }

    /**
     * Keeps the CPU up for as long as the alarm is loud.
     *
     * Not for the sound — that loops in the audio hardware and needs nothing
     * from us. It is for the coroutine above: without this, a dozing phone can
     * decline to run the timer that *stops* the alarm, and two minutes becomes
     * however long it takes for something else to wake the device.
     *
     * Timed out at the ring length plus slack, so a path that somehow skips
     * [releaseWakeLock] still cannot hold the CPU awake indefinitely.
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        wakeLock = runCatching {
            power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_TAG).apply {
                setReferenceCounted(false)
                acquire(RING_MS + WAKE_SLACK_MS)
            }
        }.getOrNull()
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private companion object {
        /** How long it stays loud. See the note above on why this is finite. */
        const val RING_MS = 120_000L

        /** The wake lock outlives the ring slightly, so it never cuts it short. */
        const val WAKE_SLACK_MS = 10_000L

        const val WAKE_TAG = "TimberTimer:rest-alarm"
    }
}
