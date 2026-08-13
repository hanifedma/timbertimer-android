package com.example.timbertimer.timer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.timbertimer.TimberApplication
import kotlinx.coroutines.launch

/**
 * Puts back a notification the user swiped away.
 *
 * The platform treats a dismissal as "I have seen this", which is the right
 * default for an alert and the wrong one for a status. A running countdown does
 * not stop because its notification was flicked aside, and neither does the
 * background sync connection — so a shade with nothing in it would be claiming
 * the app is idle while it is doing exactly the opposite. Every one of this
 * app's notifications therefore hands a delete intent here, and it reposts.
 *
 * The way out is never blocked: stopping the timer, turning background sync off
 * or turning the reminder off each removes the reason for the notification, and
 * with it the notification. Only *dismissal without a decision* is undone.
 *
 * A delete intent fires only for a dismissal by the user — the app's own
 * `cancel()` calls do not trigger one — so there is no loop to guard against.
 */
class NotificationRestorer : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTORE) return
        val container = (context.applicationContext as? TimberApplication)?.container ?: return

        val which = intent.getIntExtra(TimerNotifications.EXTRA_WHICH, 0)
        if (which == TimerNotifications.WHICH_DONE) {
            // Self-contained: the wording travels in the intent, so this works
            // even when the process died between the session finishing and the
            // swipe that brought us here.
            val title = intent.getStringExtra(TimerNotifications.EXTRA_TITLE)
            val text = intent.getStringExtra(TimerNotifications.EXTRA_TEXT)
            if (title != null && text != null) container.notifications.showCompleted(title, text)
            return
        }

        // The other two describe live state, so they are rebuilt from it rather
        // than from anything the intent carried.
        val pending = goAsync()
        container.scope.launch {
            try {
                container.timerEngine.restoreNotification(which)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESTORE = "com.example.timbertimer.RESTORE_NOTIFICATION"
    }
}
