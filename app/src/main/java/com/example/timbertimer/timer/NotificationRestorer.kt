package com.example.timbertimer.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.timbertimer.TimberApplication
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.local.LocalStore
import kotlinx.coroutines.launch

/**
 * Puts back the ongoing notification when the user swipes it away.
 *
 * The platform treats a dismissal as "I have seen this", which is the right
 * default for an alert and the wrong one for a status. A running countdown does
 * not stop because its notification was flicked aside, and neither does the
 * background sync connection — so a shade with nothing in it would be claiming
 * the app is idle while it is doing exactly the opposite.
 *
 * The finished-session alert and the idle nudge are *not* restored: they report
 * on nothing that is still running, so a swipe is simply the user having seen
 * them, and reposting would leave an alert with no way to dismiss it at all.
 *
 * The rest alarm is restored, for the opposite reason to the ongoing
 * notification rather than the same one. It is not a status — it is an
 * instruction the user asked for — and a swipe from a lock screen is the most
 * common reflex there is. It has explicit buttons that end it, so restoring it
 * traps nobody: Dismiss clears it, and so does opening the app.
 *
 * The way out is never blocked in either case: stopping the timer or turning
 * background sync off removes the reason for the ongoing notification, and with
 * it the notification. Only *dismissal without a decision* is undone.
 *
 * A delete intent fires only for a dismissal by the user — the app's own
 * `cancel()` calls do not trigger one — so there is no loop to guard against.
 */
class NotificationRestorer : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? TimberApplication)?.container ?: return

        when (intent.action) {
            ACTION_RESTORE -> {
                // Rebuilt from live state rather than reposted verbatim, because
                // the process may have been recycled since it was last built.
                val pending = goAsync()
                container.scope.launch {
                    try {
                        container.timerEngine.restoreOngoingNotification()
                    } finally {
                        pending.finish()
                    }
                }
            }

            ACTION_RESTORE_ALARM -> {
                // Synchronously: this is a repost of something already built,
                // and the alarm should not blink out of the shade while a
                // coroutine is scheduled.
                val alarm = container.restAlarm
                val ringing = alarm.ringing.value ?: return
                container.notifications.showRestAlarm(ringing.durationMinutes, ringing.loud)
            }

            /**
             * The rest count, put back after something removed it.
             *
             * Read from the stored snapshot rather than from the repository:
             * this broadcast can be what starts the process, and the records
             * would not have loaded yet. Synchronously for the same reason the
             * alarm is — a number that blinks out and returns a moment later
             * looks broken, where one that never left looks deliberate.
             *
             * The switch is checked here too, not only at the call sites. The
             * delete intent is already sitting on a posted notification by the
             * time the user turns the feature off, and the shade can fire it
             * afterwards; without this, switching it off and then swiping would
             * bring it back.
             */
            ACTION_RESTORE_TALLY -> {
                if (!container.settings.restTally.value) return
                val today = Time.localDateKey(System.currentTimeMillis())
                val totals = LocalStore(context).readTodayTotals().forDay(today)
                container.notifications.showRestTally(totals.rests, totals.restMinutes)
            }
        }
    }

    companion object {
        const val ACTION_RESTORE = "com.example.timbertimer.RESTORE_NOTIFICATION"
        const val ACTION_RESTORE_ALARM = "com.example.timbertimer.RESTORE_REST_ALARM"
        const val ACTION_RESTORE_TALLY = "com.example.timbertimer.RESTORE_REST_TALLY"
    }
}
