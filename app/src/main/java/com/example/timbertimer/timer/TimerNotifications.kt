package com.example.timbertimer.timer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.timbertimer.MainActivity
import com.example.timbertimer.R
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.RestTimer
import com.example.timbertimer.data.model.TimerMode
import com.example.timbertimer.data.model.TreeSpecies

/**
 * The running timer as it appears outside the app: an ongoing notification on
 * the lock screen and in the shade, and an alert the moment a session lands.
 *
 * The time itself is drawn by the platform's chronometer rather than by
 * restating the text every second. It counts down on its own once given the
 * instant to count to, which keeps the display exact while letting the service
 * repost only occasionally to move the progress bar.
 */
class TimerNotifications(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        // DEFAULT rather than LOW, with the sound and vibration stripped out.
        //
        // Importance is what decides which bucket a notification lands in, and
        // LOW lands it under "Silent" — which most lock screens, and nearly every
        // OEM skin, hide by default. The running timer is the one thing that has
        // to be readable without unlocking, so it is filed as a normal
        // notification that simply never makes a sound.
        val running = NotificationChannel(
            CHANNEL_RUNNING,
            context.getString(R.string.notif_channel_running),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_running_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val done = NotificationChannel(
            CHANNEL_DONE,
            context.getString(R.string.notif_channel_done),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_done_desc)
            // The chime and the buzz are played by the app itself so the in-app
            // sound and vibration switches actually govern them. Leaving the
            // channel to do it too would double every alert and ignore both.
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val idle = NotificationChannel(
            CHANNEL_IDLE,
            context.getString(R.string.notif_channel_idle),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_idle_desc)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val service = context.getSystemService(NotificationManager::class.java)
        // A channel's importance is fixed once created, so raising it needs a new
        // id. Retire the old one or it lingers in the app's notification settings
        // as a dead entry the user can still toggle.
        runCatching { service?.deleteNotificationChannel(RETIRED_CHANNEL_RUNNING) }
        service?.createNotificationChannel(running)
        service?.createNotificationChannel(done)
        service?.createNotificationChannel(idle)
    }

    /** The ongoing notification. Focus takes precedence when both are running. */
    fun buildOngoing(timer: ActiveTimer?, rest: RestTimer?): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_tree)
            .setColor(ContextCompat.getColor(context, R.color.timber_accent))
            .setContentIntent(openApp())
            .setOngoing(true)
            // Not setSilent(): the channel already has no sound and no vibration,
            // so it adds nothing but the SILENT flag — which is one more thing an
            // OEM lock screen can use as a reason to filter it out.
            .setShowWhen(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            // Android 16 promotes a live, ongoing notification to a status bar
            // chip and a prominent lock screen slot — which is exactly what a
            // running timer is. Older versions ignore the request.
            .setRequestPromotedOngoing(true)

        if (timer != null) {
            builder.setContentTitle(timer.title)
            if (timer.mode == TimerMode.COUNTDOWN) {
                builder
                    // `when` is the instant to count toward; the platform draws
                    // the countdown from it, live, with no further posts.
                    .setWhen(timer.endAt)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(true)
                    .setContentText(
                        context.getString(
                            R.string.notif_focus_countdown,
                            Time.formatClock(timer.remainingSeconds()),
                        )
                    )
                    .setProgress(1000, (timer.progress() * 1000).toInt(), false)
            } else {
                builder
                    .setWhen(timer.startedAt)
                    .setUsesChronometer(true)
                    .setChronometerCountDown(false)
                    .setContentText(context.getString(R.string.notif_focus_stopwatch))
            }
            builder.addAction(
                0,
                context.getString(R.string.notif_action_finish),
                serviceAction(TimerService.ACTION_FINISH),
            )
            if (timer.mode == TimerMode.COUNTDOWN) {
                builder.addAction(
                    0,
                    context.getString(R.string.notif_action_give_up),
                    serviceAction(TimerService.ACTION_GIVE_UP),
                )
            }
        } else if (rest != null) {
            builder
                .setContentTitle(context.getString(R.string.notif_rest_title))
                .setContentText(context.getString(R.string.notif_rest_text))
                .setWhen(rest.startedAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
                .addAction(
                    0,
                    context.getString(R.string.notif_action_finish),
                    serviceAction(TimerService.ACTION_FINISH_REST),
                )
        } else {
            // Nothing is running, but the service is: this is the background-sync
            // state. It says what the app is actually doing rather than a bare
            // app name, since it is the notification the user will see most.
            builder
                .setContentTitle(context.getString(R.string.notif_sync_title))
                .setContentText(context.getString(R.string.notif_sync_text))
                .setShowWhen(false)
                .setUsesChronometer(false)
        }

        return builder.build()
    }

    /** Fired when a session finishes, so it is noticed from the lock screen. */
    fun showCompleted(record: FocusRecord) {
        if (!canPost()) return

        val species = TreeSpecies.byLabelOrId(record.treeKind) ?: TreeSpecies.PINE
        val text = context.getString(
            R.string.notif_done_text,
            record.title,
            Time.formatMinutes(
                record.actualMinutes,
                context.getString(R.string.unit_m),
                context.getString(R.string.unit_h),
            ),
            context.getString(species.displayRes).lowercase(),
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_tree)
            .setColor(ContextCompat.getColor(context, R.color.timber_accent))
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        runCatching { manager.notify(ID_DONE, notification) }
    }

    /**
     * The nudge shown while nothing is running.
     *
     * Deliberately low importance, silent and dismissible: it is an invitation
     * sitting in the shade, not an alarm. It names what has already been done
     * today, because that reads as encouragement rather than nagging.
     */
    fun showIdle(todayMinutes: Int) {
        if (!canPost()) return

        val text = if (todayMinutes <= 0) {
            context.getString(R.string.notif_idle_empty)
        } else {
            context.getString(
                R.string.notif_idle_progress,
                Time.formatMinutes(
                    todayMinutes,
                    context.getString(R.string.unit_m),
                    context.getString(R.string.unit_h),
                ),
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_IDLE)
            .setSmallIcon(R.drawable.ic_stat_tree)
            .setColor(ContextCompat.getColor(context, R.color.timber_accent))
            .setContentTitle(context.getString(R.string.notif_idle_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, context.getString(R.string.notif_idle_action), openApp())
            .build()

        runCatching { manager.notify(ID_IDLE, notification) }
    }

    fun cancelIdle() {
        runCatching { manager.cancel(ID_IDLE) }
    }

    fun update(timer: ActiveTimer?, rest: RestTimer?) {
        if (!canPost()) return
        runCatching { manager.notify(ID_RUNNING, buildOngoing(timer, rest)) }
    }

    fun cancelOngoing() {
        runCatching { manager.cancel(ID_RUNNING) }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun openApp(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(context, 0, intent, IMMUTABLE)
    }

    private fun serviceAction(action: String): PendingIntent {
        val intent = Intent(context, TimerService::class.java).setAction(action)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, action.hashCode(), intent, IMMUTABLE)
        } else {
            PendingIntent.getService(context, action.hashCode(), intent, IMMUTABLE)
        }
    }

    companion object {
        const val CHANNEL_RUNNING = "timer-running-v2"

        /** The LOW-importance channel shipped in 1.0; replaced, not reused. */
        private const val RETIRED_CHANNEL_RUNNING = "timer-running"
        const val CHANNEL_DONE = "timer-done"
        const val CHANNEL_IDLE = "timer-idle"
        const val ID_RUNNING = 1001
        const val ID_DONE = 1002
        const val ID_IDLE = 1003

        private const val IMMUTABLE =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
