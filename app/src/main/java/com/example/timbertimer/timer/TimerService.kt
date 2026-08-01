package com.example.timbertimer.timer

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.example.timbertimer.TimberApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the app alive and on screen while a timer runs.
 *
 * It does no timekeeping of its own — [TimerEngine] does that, from the clock.
 * What this buys is process priority (so the countdown is not killed the moment
 * the user switches away) and the ongoing notification the platform requires in
 * exchange.
 */
class TimerService : Service() {

    private var scope: CoroutineScope? = null
    private var lastPostedAt = 0L

    private val container get() = (application as TimberApplication).container

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

        serviceScope.launch {
            val engine = container.timerEngine
            combine(engine.timer, engine.rest, engine.now) { timer, rest, _ -> timer to rest }
                .collect { (timer, rest) ->
                    if (timer == null && rest == null) {
                        stopSelf()
                        return@collect
                    }
                    refreshNotification(timer != null, rest != null)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()

        when (intent?.action) {
            ACTION_FINISH -> runInApp { container.timerEngine.finish() }
            ACTION_GIVE_UP -> runInApp { container.timerEngine.giveUp() }
            ACTION_FINISH_REST -> runInApp { container.timerEngine.finishRest() }
        }

        // A timer the user started should come back if the process is recycled.
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        container.notifications.cancelOngoing()
        super.onDestroy()
    }

    /**
     * Reposts the notification, but not every second.
     *
     * The elapsed and remaining time are drawn by the platform's own chronometer,
     * which updates itself. Only the progress bar needs a repost, and doing that
     * once every few seconds keeps the shade from being rewritten sixty times a
     * minute for a bar that moves a pixel.
     */
    private fun refreshNotification(hasTimer: Boolean, hasRest: Boolean) {
        if (!hasTimer && !hasRest) return
        val now = System.currentTimeMillis()
        if (now - lastPostedAt < REPOST_INTERVAL_MS) return
        lastPostedAt = now
        container.notifications.update(container.timerEngine.timer.value, container.timerEngine.rest.value)
    }

    private fun promote() {
        val notification = container.notifications.buildOngoing(
            container.timerEngine.timer.value,
            container.timerEngine.rest.value,
        )
        lastPostedAt = System.currentTimeMillis()

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
        const val ACTION_GIVE_UP = "com.example.timbertimer.GIVE_UP"
        const val ACTION_FINISH_REST = "com.example.timbertimer.FINISH_REST"

        /** Fast enough that the progress bar tracks, slow enough to be free. */
        private const val REPOST_INTERVAL_MS = 5_000L

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
