package com.example.timbertimer.timer

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.timbertimer.TimberApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app alive: while a timer runs, and — if background sync is on —
 * whenever the app is installed at all.
 *
 * It does no timekeeping of its own; [TimerEngine] does that, from the clock.
 * What this buys is process priority, and that is the whole point twice over: a
 * countdown that is not killed the moment the user switches away, and a live
 * sync connection that outlives the last open screen. Without the second, the
 * process died with the UI, so a task ticked on one device sat unnoticed in
 * another device's widget until something opened the app there.
 *
 * The permanent notification is what the platform charges for this.
 */
class TimerService : Service() {

    private var scope: CoroutineScope? = null
    private var lastPostedAt = 0L
    private var lastVerifiedAt = 0L
    private var postedIdle = false

    private val container get() = (application as TimberApplication).container

    /**
     * Battery saver turning on, Doze letting go, the screen waking: each is a
     * moment a vendor power manager may have swept the shade on the way past,
     * and each is cheap to check. Registered here rather than in the manifest
     * because most of these cannot be declared there — and because there is
     * nothing to check when the service is not running anyway.
     */
    private val powerEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            repostIfMissing()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        container.notifications.ensureChannels()
        val job = SupervisorJob()
        val serviceScope = CoroutineScope(Dispatchers.Main.immediate + job)
        scope = serviceScope

        // Enter the foreground straight away: the platform allows only a few
        // seconds between starting and calling startForeground.
        promote()
        registerPowerEvents()

        serviceScope.launch {
            val engine = container.timerEngine
            combine(
                engine.timer,
                engine.rest,
                container.restAlarm.ringing,
                engine.now,
            ) { timer, rest, alarm, _ -> Triple(timer, rest, alarm) }
                .collect { (timer, rest, alarm) ->
                    // Nothing running is no longer a reason to stop: background
                    // sync is the service's other job, and it is the only thing
                    // keeping the live socket — and so the widget — current. A
                    // ringing alarm is a third, and the one whose absence would
                    // be heard: the process dying takes the noise with it.
                    if (timer == null && rest == null && alarm == null &&
                        !container.settings.backgroundSync.value
                    ) {
                        stopSelf()
                        return@collect
                    }
                    refreshNotification(idle = timer == null && rest == null)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()

        when (intent?.action) {
            ACTION_FINISH -> runInApp { container.timerEngine.finish() }
            ACTION_FINISH_REST -> runInApp { container.timerEngine.finishRest() }
            // Answered synchronously: the user has pressed a button to stop a
            // noise, and the noise should stop on the press rather than
            // whenever a coroutine next gets the main thread.
            ACTION_DISMISS_REST_ALARM -> container.restAlarm.dismiss()
        }

        // A timer the user started should come back if the process is recycled.
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        runCatching { unregisterReceiver(powerEvents) }
        container.notifications.cancelOngoing()
        super.onDestroy()
    }

    /**
     * The user swiped the app out of Recents.
     *
     * Several manufacturers treat that as "kill everything this app owns",
     * service and all, which is the single most common way a running timer
     * disappears. START_STICKY is supposed to bring the service back and
     * usually does; re-arming the heartbeat here is the backstop for the builds
     * where it does not.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        container.timerEngine.onWatchdogTick()
        super.onTaskRemoved(rootIntent)
    }

    private fun registerPowerEvents() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }
        runCatching {
            ContextCompat.registerReceiver(
                this,
                powerEvents,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    /** Repost only when the notification really has gone, not on every event. */
    private fun repostIfMissing() {
        if (container.notifications.isShowing(TimerNotifications.ID_RUNNING)) return
        promote()
    }

    /**
     * Reposts the notification, but not every second.
     *
     * The elapsed and remaining time are drawn by the platform's own chronometer,
     * which updates itself. Only the progress bar needs a repost, and doing that
     * once every few seconds keeps the shade from being rewritten sixty times a
     * minute for a bar that moves a pixel.
     */
    private fun refreshNotification(idle: Boolean) {
        val now = System.currentTimeMillis()
        // Idle, the seconds are drawn by the platform's own chronometer, so the
        // only thing a repost buys is the sentence beside it rolling over from
        // "2h 15m" to "2h 16m" — worth a minute, not worth five seconds.
        val interval = if (idle) IDLE_REPOST_INTERVAL_MS else REPOST_INTERVAL_MS
        var due = if (idle && !postedIdle) true else now - lastPostedAt >= interval

        // Neither schedule notices a notification that was removed without the
        // app being told — the idle one least of all, since it is posted once
        // and then never again. Asking the system now and then is what closes
        // that gap; it is a binder call, so not every second.
        if (!due && now - lastVerifiedAt >= VERIFY_INTERVAL_MS) {
            lastVerifiedAt = now
            due = !container.notifications.isShowing(TimerNotifications.ID_RUNNING)
        }
        if (!due) return

        lastPostedAt = now
        postedIdle = idle
        container.notifications.update(
            container.timerEngine.timer.value,
            container.timerEngine.rest.value,
            container.timerEngine.idleSummary(),
        )
    }

    private fun promote() {
        val notification = container.notifications.buildOngoing(
            container.timerEngine.timer.value,
            container.timerEngine.rest.value,
            container.timerEngine.idleSummary(),
        )
        lastPostedAt = System.currentTimeMillis()
        postedIdle = container.timerEngine.timer.value == null &&
            container.timerEngine.rest.value == null

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    TimerNotifications.ID_RUNNING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(TimerNotifications.ID_RUNNING, notification)
            }
        }
    }

    private fun runInApp(block: suspend () -> Unit) {
        container.scope.launch { block() }
    }

    companion object {
        const val ACTION_FINISH = "com.example.timbertimer.FINISH"
        const val ACTION_FINISH_REST = "com.example.timbertimer.FINISH_REST"
        const val ACTION_DISMISS_REST_ALARM = "com.example.timbertimer.DISMISS_REST_ALARM"

        /** Fast enough that the progress bar tracks, slow enough to be free. */
        private const val REPOST_INTERVAL_MS = 5_000L

        /** Only the minute figure moves while nothing is running. */
        private const val IDLE_REPOST_INTERVAL_MS = 60_000L

        /** How often to ask the system whether the notification is still there. */
        private const val VERIFY_INTERVAL_MS = 20_000L

        fun start(context: Context) {
            val intent = Intent(context, TimerService::class.java)
            // Foreground starts are restricted from the background, and a timer
            // adopted from another device can arrive while the app is away.
            // Failing to show the notification must not take the timer with it.
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TimerService::class.java)) }
        }
    }
}
