package com.example.timbertimer.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.timbertimer.TimberApplication
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
 * This applies to that one notification and no other. The finished-session
 * alert and the idle nudge report on nothing that is still running, so a swipe
 * is simply the user having seen them, and reposting would leave an alert with
 * no way to dismiss it at all.
 *
 * The way out is never blocked here either: stopping the timer or turning
 * background sync off removes the reason for the notification, and with it the
 * notification. Only *dismissal without a decision* is undone.
 *
 * A delete intent fires only for a dismissal by the user — the app's own
 * `cancel()` calls do not trigger one — so there is no loop to guard against.
 */
class NotificationRestorer : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTORE) return
        val container = (context.applicationContext as? TimberApplication)?.container ?: return

        // Rebuilt from live state rather than reposted verbatim, because the
        // process may have been recycled since it was last built.
        val pending = goAsync()
        container.scope.launch {
            try {
                container.timerEngine.restoreOngoingNotification()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESTORE = "com.example.timbertimer.RESTORE_NOTIFICATION"
    }
}
