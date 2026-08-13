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
import androidx.annotation.RequiresApi
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
 * The app as it appears outside itself: an ongoing notification on the lock
 * screen and in the shade, an alert the moment a session lands, and a quiet
 * nudge when nothing is growing.
 *
 * The time itself is drawn by the platform's chronometer rather than by
 * restating the text every second. It counts down on its own once given the
 * instant to count to, which keeps the display exact while letting the service
 * repost only occasionally to move the progress bar.
 *
 * Every notification here carries a delete intent. Dismissing one is not an
 * instruction to stop — the timer keeps running, the sync connection stays
 * open — so the shade would otherwise start lying about what the app is doing.
 * [NotificationRestorer] puts it straight back.
 */
class TimerNotifications(context: Context) {

    private val context = context.applicationContext

    private val manager = NotificationManagerCompat.from(this.context)

    /**
     * Which channel generation this install settled on. A channel's Do Not
     * Disturb bypass can only be set when it is created, and only by an app the
     * user has granted policy access, so gaining that access means starting a
     * new generation rather than editing the old one.
     */
    private val prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Creates the channels, and reports whether that moved them to a new
     * generation — which invalidates anything currently posted, because the
     * platform cancels the notifications on a channel it deletes.
     */
    fun ensureChannels(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val service = context.getSystemService(NotificationManager::class.java) ?: return false

        val moved = advanceGeneration()

        // DEFAULT rather than LOW, with the sound and vibration stripped out.
        //
        // Importance is what decides which bucket a notification lands in, and
        // LOW lands it under "Silent" — which most lock screens, and nearly every
        // OEM skin, hide by default. The running timer is the one thing that has
        // to be readable without unlocking, so it is filed as a normal
        // notification that simply never makes a sound.
        val running = NotificationChannel(
            runningChannel,
            context.getString(R.string.notif_channel_running),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_running_desc)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            allowThroughDnd()
        }

        val done = NotificationChannel(
            doneChannel,
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
            allowThroughDnd()
        }

        val idle = NotificationChannel(
            idleChannel,
            context.getString(R.string.notif_channel_idle),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_idle_desc)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        // A channel's importance is fixed once created, so raising it needs a new
        // id. Retire the old ones or they linger in the app's notification
        // settings as dead entries the user can still toggle.
        val live = setOf(runningChannel, doneChannel, idleChannel)
        for (retired in RETIRED_CHANNELS) {
            if (retired !in live) runCatching { service.deleteNotificationChannel(retired) }
        }

        service.createNotificationChannel(running)
        service.createNotificationChannel(done)
        service.createNotificationChannel(idle)
        return moved
    }

    /** The ongoing notification. Focus takes precedence when both are running. */
    fun buildOngoing(timer: ActiveTimer?, rest: RestTimer?): Notification {
        val builder = NotificationCompat.Builder(context, runningChannel)
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
            // From Android 14 an ongoing foreground-service notification can be
            // swiped away like any other. The service is still running when that
            // happens, so the notification comes straight back rather than
            // leaving a timer counting down with nothing on screen.
            .setDeleteIntent(restoreIntent(WHICH_ONGOING))
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
        val species = TreeSpecies.byLabelOrId(record.treeKind) ?: TreeSpecies.PINE
        showCompleted(
            title = context.getString(R.string.notif_done_title),
            text = context.getString(
                R.string.notif_done_text,
                record.title,
                Time.formatMinutes(
                    record.actualMinutes,
                    context.getString(R.string.unit_m),
                    context.getString(R.string.unit_h),
                ),
                context.getString(species.displayRes).lowercase(),
            ),
        )
    }

    /**
     * Posts — or reposts — the finished-session alert.
     *
     * The wording travels in the delete intent rather than being looked up
     * again, because the process may have been recycled between the session
     * finishing and the user swiping the alert away.
     */
    fun showCompleted(title: String, text: String) {
        if (!canPost()) return

        val notification = NotificationCompat.Builder(context, doneChannel)
            .setSmallIcon(R.drawable.ic_stat_tree)
            .setColor(ContextCompat.getColor(context, R.color.timber_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            // Tapping it opens the forest and clears it for good; swiping it
            // aside does not, because a finished session the user never saw is
            // the one thing this app must not lose quietly.
            .setAutoCancel(true)
            .setDeleteIntent(
                restoreIntent(WHICH_DONE) { intent ->
                    intent.putExtra(EXTRA_TITLE, title).putExtra(EXTRA_TEXT, text)
                }
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // ALARM is the category Do Not Disturb lets through under its own
            // "allow alarms" rule, which is the default on every Android build.
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        runCatching { manager.notify(ID_DONE, notification) }
    }

    /**
     * The nudge shown while nothing is running.
     *
     * Deliberately low importance and silent: it is an invitation sitting in the
     * shade, not an alarm. It names what has already been done today, because
     * that reads as encouragement rather than nagging.
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

        val notification = NotificationCompat.Builder(context, idleChannel)
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
            // Swiping it away does not switch the reminder off — the switch in
            // Settings does. Until then the app's state stays on show.
            .setDeleteIntent(restoreIntent(WHICH_IDLE))
            .addAction(0, context.getString(R.string.notif_idle_action), openApp())
            .build()

        runCatching { manager.notify(ID_IDLE, notification) }
    }

    fun cancelIdle() {
        runCatching { manager.cancel(ID_IDLE) }
    }

    /** Taken as read once the user is looking at the app itself. */
    fun cancelCompleted() {
        runCatching { manager.cancel(ID_DONE) }
    }

    fun update(timer: ActiveTimer?, rest: RestTimer?) {
        if (!canPost()) return
        runCatching { manager.notify(ID_RUNNING, buildOngoing(timer, rest)) }
    }

    fun cancelOngoing() {
        runCatching { manager.cancel(ID_RUNNING) }
    }

    /**
     * True when the notification with [id] is currently in the shade.
     *
     * A dismissal by the user arrives as a delete intent, but a notification can
     * also go missing without one — a vendor "cleaner" sweeping the shade, or
     * the system dropping it along with the process. Asking is the only way to
     * find out, and an unanswerable question is treated as "still there" so a
     * failed check never turns into a repost loop.
     */
    fun isShowing(id: Int): Boolean {
        val service = context.getSystemService(NotificationManager::class.java) ?: return true
        return runCatching { service.activeNotifications.any { it.id == id } }.getOrDefault(true)
    }

    /**
     * Whether alerts may sound through Do Not Disturb.
     *
     * Purely a report for the Settings screen: the app cannot grant itself this,
     * and without it the platform silently ignores the request on every channel.
     */
    fun canBypassDoNotDisturb(): Boolean = hasDndAccess()

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

    private fun restoreIntent(which: Int, extras: (Intent) -> Unit = {}): PendingIntent {
        val intent = Intent(context, NotificationRestorer::class.java)
            .setAction(NotificationRestorer.ACTION_RESTORE)
            .putExtra(EXTRA_WHICH, which)
            .also(extras)
        // FLAG_UPDATE_CURRENT is part of IMMUTABLE, so the extras on a reposted
        // alert replace the ones the previous post left behind.
        return PendingIntent.getBroadcast(context, which, intent, IMMUTABLE)
    }

    // ---------- Do Not Disturb ----------

    /**
     * Asks for the channel to be heard through Do Not Disturb.
     *
     * The platform honours this only for an app the user has given notification
     * policy access, and only at the moment the channel is created — which is
     * why gaining that access starts a new channel generation rather than
     * editing the existing one. Without access it is quietly dropped, so it is
     * always safe to ask.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun NotificationChannel.allowThroughDnd() {
        if (hasDndAccess()) runCatching { setBypassDnd(true) }
    }

    private fun hasDndAccess(): Boolean {
        val service = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { service.isNotificationPolicyAccessGranted }.getOrDefault(false)
    }

    /**
     * "" until the user grants policy access, then permanently [DND_SUFFIX].
     *
     * Latched rather than recomputed: revoking access later would otherwise move
     * every notification to a fresh channel and throw away whatever the user had
     * configured on the old one.
     *
     * Only [ensureChannels] ever advances it. Letting a builder do so would post
     * to a channel that had not been created yet, and the platform drops those.
     */
    @Volatile
    private var generation: String =
        if (prefs.getBoolean(KEY_DND_CHANNELS, false)) DND_SUFFIX else ""

    /** True when this call moved to a new generation, so live posts are stale. */
    private fun advanceGeneration(): Boolean {
        if (generation == DND_SUFFIX || !hasDndAccess()) return false
        prefs.edit().putBoolean(KEY_DND_CHANNELS, true).apply()
        generation = DND_SUFFIX
        return true
    }

    private val runningChannel: String get() = CHANNEL_RUNNING + generation
    private val doneChannel: String get() = CHANNEL_DONE + generation
    private val idleChannel: String get() = CHANNEL_IDLE + generation

    companion object {
        const val CHANNEL_RUNNING = "timer-running-v2"
        const val CHANNEL_DONE = "timer-done"
        const val CHANNEL_IDLE = "timer-idle"

        /** Marks the channel generation created with Do Not Disturb bypass. */
        private const val DND_SUFFIX = "-dnd"

        /**
         * Every channel id this app has ever used. Whichever are not in service
         * right now are deleted, so the app's notification settings list what the
         * app actually posts and nothing else.
         */
        private val RETIRED_CHANNELS = listOf(
            // The LOW-importance running channel shipped in 1.0.
            "timer-running",
            CHANNEL_RUNNING,
            CHANNEL_DONE,
            CHANNEL_IDLE,
            CHANNEL_RUNNING + DND_SUFFIX,
            CHANNEL_DONE + DND_SUFFIX,
            CHANNEL_IDLE + DND_SUFFIX,
        )

        const val ID_RUNNING = 1001
        const val ID_DONE = 1002
        const val ID_IDLE = 1003

        /** Which notification a delete intent belongs to. */
        const val EXTRA_WHICH = "com.example.timbertimer.WHICH"
        const val EXTRA_TITLE = "com.example.timbertimer.TITLE"
        const val EXTRA_TEXT = "com.example.timbertimer.TEXT"

        const val WHICH_ONGOING = 1
        const val WHICH_DONE = 2
        const val WHICH_IDLE = 3

        private const val PREFS = "timber-notifications"
        private const val KEY_DND_CHANNELS = "dnd-channels"

        private const val IMMUTABLE =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
