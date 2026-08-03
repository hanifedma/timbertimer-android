package com.example.timbertimer.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.timbertimer.TimberApplication
import kotlinx.coroutines.launch

/**
 * The safety net under the foreground service.
 *
 * The service is what normally notices a countdown reaching zero, and while it
 * is alive that is enough. But a foreground service can still be torn down — by
 * an aggressive vendor battery manager, or by the system reclaiming memory under
 * pressure — and a focus timer that silently fails to go off is worse than one
 * that never started. This alarm fires at the exact instant regardless, wakes
 * the process, and finishes the session.
 *
 * Both paths converge on the same guarded [TimerEngine] call, so whichever
 * arrives second finds the work already done.
 */
class TimerAlarms(context: Context) {

    private val appContext = context.applicationContext

    private val manager: AlarmManager? =
        appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun schedule(triggerAtMillis: Long) {
        val alarmManager = manager ?: return
        val pending = pendingIntent()

        // On API 31+ exact alarms need permission. USE_EXACT_ALARM is declared
        // and install-granted for timer apps, but a vendor build that withholds
        // it should degrade to an inexact alarm rather than crash.
        val canBeExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()

        runCatching {
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending,
                )
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pending,
                )
            }
        }
    }

    fun cancel() {
        runCatching { manager?.cancel(pendingIntent()) }
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, TimerAlarmReceiver::class.java).setAction(ACTION_DUE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_DUE = "com.example.timbertimer.TIMER_DUE"
        private const val REQUEST_CODE = 4201
    }
}

/**
 * Receives the backstop alarm. The process may have been dead until this moment,
 * so the engine is re-read from disk and reconciled before anything else.
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as? TimberApplication)?.container ?: return
        val pending = goAsync()

        container.scope.launch {
            try {
                container.timerEngine.hydrate()
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Restores the ongoing notification for a timer that outlived a reboot or an
 * app update. Because the timer is stored as an instant rather than a remaining
 * count, one that came due while the phone was off is simply due now.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val container = (context.applicationContext as? TimberApplication)?.container ?: return
        val pending = goAsync()

        // Synchronously, and before anything that can suspend: the permission to
        // start a foreground service from the background is granted here only as
        // a short allowance tied to this broadcast, and it expires.
        container.timerEngine.onBackgroundSyncChanged()

        container.scope.launch {
            try {
                container.timerEngine.hydrate()
            } finally {
                pending.finish()
            }
        }
    }
}
