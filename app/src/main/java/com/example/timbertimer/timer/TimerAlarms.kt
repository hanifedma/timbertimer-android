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

    /**
     * The same backstop for a rest countdown, on its own request code.
     *
     * It has to be a second alarm rather than a reused one: a focus session and
     * a rest can run at the same time, and a single PendingIntent would have
     * whichever was armed second silently cancel the first — which is the sort
     * of bug that only shows up for the users who do both, and only sometimes.
     *
     * If anything, this one matters more than the focus alarm. A focus session
     * that finishes late still plants its tree for the time it actually ran; a
     * rest that finishes late is a rest that did not end when the user asked it
     * to, which is the entire failure this feature exists to prevent.
     */
    fun scheduleRest(triggerAtMillis: Long) {
        val alarmManager = manager ?: return
        val pending = restIntent()

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

    fun cancelRest() {
        runCatching { manager?.cancel(restIntent()) }
    }

    /**
     * Schedules the next heartbeat: one wake-up, a quarter of an hour out, that
     * checks the app is still where it said it would be.
     *
     * This is what covers the failure the completion alarm cannot. That one only
     * fires when a countdown is due, so a process killed while a *stopwatch* ran
     * — or while nothing ran but background sync was on — stayed dead, taking
     * the notification with it and leaving the shade silently wrong. The
     * heartbeat notices and puts everything back.
     *
     * [urgent] is what stops this costing battery for nothing. A running timer
     * earns an exact wake-up: the notification is the timer as far as the user
     * is concerned, and an exact alarm is also one of the few things the
     * platform still lets restart a foreground service from the background. With
     * nothing running it is only the sync notification at stake, so the wake-up
     * is left inexact and Doze batches it into the next maintenance window —
     * which arrives the moment the phone is picked up, and that is the only
     * moment anyone can see the shade anyway.
     *
     * It re-arms itself each time, so there is exactly one outstanding.
     */
    fun scheduleWatchdog(urgent: Boolean) {
        val alarmManager = manager ?: return
        val at = System.currentTimeMillis() + WATCHDOG_INTERVAL_MS
        val exact = urgent && (
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            )

        runCatching {
            if (exact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogIntent())
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, watchdogIntent())
            }
        }
    }

    fun cancelWatchdog() {
        runCatching { manager?.cancel(watchdogIntent()) }
    }

    private fun pendingIntent(): PendingIntent = broadcast(REQUEST_CODE, ACTION_DUE)

    private fun restIntent(): PendingIntent = broadcast(REST_REQUEST_CODE, ACTION_REST_DUE)

    private fun watchdogIntent(): PendingIntent = broadcast(WATCHDOG_REQUEST_CODE, ACTION_WATCHDOG)

    private fun broadcast(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            requestCode,
            Intent(appContext, TimerAlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        const val ACTION_DUE = "com.example.timbertimer.TIMER_DUE"
        const val ACTION_REST_DUE = "com.example.timbertimer.REST_DUE"
        const val ACTION_WATCHDOG = "com.example.timbertimer.WATCHDOG"
        private const val REQUEST_CODE = 4201
        private const val WATCHDOG_REQUEST_CODE = 4202
        private const val REST_REQUEST_CODE = 4203

        /**
         * Fifteen minutes: the shortest interval Doze will honour for a
         * repeating wake-up on every Android version, so asking for less would
         * cost battery without arriving any sooner.
         */
        private const val WATCHDOG_INTERVAL_MS = 15 * 60 * 1000L
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

        if (intent.action == TimerAlarms.ACTION_WATCHDOG) {
            // Synchronously, and before anything that can suspend: restarting a
            // foreground service from the background is allowed here only as a
            // short-lived grant tied to this exact alarm, and it expires.
            container.timerEngine.onWatchdogTick()
        }

        container.scope.launch {
            try {
                // The rest is answered from the clock before the network is
                // touched. hydrate() would get there eventually, but it fetches
                // two shared rows first, and on a phone that has just woken up
                // with no signal that is seconds of silence — during which the
                // user is waiting for an alarm they set.
                if (intent.action == TimerAlarms.ACTION_REST_DUE) {
                    container.timerEngine.onRestDue()
                }
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
        // Several manufacturers' "fast boot" never broadcasts BOOT_COMPLETED and
        // sends one of the QUICKBOOT actions instead, which is why a timer used
        // to disappear over a restart on exactly those phones.
        if (intent.action !in HANDLED) return

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

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
