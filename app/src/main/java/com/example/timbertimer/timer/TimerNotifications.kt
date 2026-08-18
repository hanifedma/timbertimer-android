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
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
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
 * restating the text every second. It counts on its own once given the instant
 * to count to or from, which keeps the display exact while letting the service
 * repost only occasionally.
 *
 * The ongoing one, and only the ongoing one, is put back when it is swiped
 * away. Dismissing that is not an instruction to stop — the timer keeps
 * running, the sync connection stays open — so the shade would otherwise start
 * lying about what the app is doing, and [NotificationRestorer] puts it
 * straight back. The other two describe nothing that is still happening, so for
 * those a swipe means what it says and they stay gone.
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

        // The rest alarm gets its own channel rather than sharing "done", for a
        // reason that outlives this code: a channel is the unit the *user*
        // turns off. Someone who mutes the session chime because it interrupts
        // their work has said nothing about wanting to sleep through the end of
        // a break, and sharing one channel would take that choice away from
        // them. It also means the alarm can be silenced on its own if they do
        // find it too much, without losing everything else.
        val restAlarm = NotificationChannel(
            restAlarmChannel,
            context.getString(R.string.notif_channel_rest_alarm),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_rest_alarm_desc)
            setShowBadge(true)
            // Played by the app, for the same reason the others are: the
            // channel cannot loop, cannot escalate, and cannot be stopped by a
            // button. See TimerFeedback.startRestAlarm.
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            allowThroughDnd()
        }

        // A channel's importance is fixed once created, so raising it needs a new
        // id. Retire the old ones or they linger in the app's notification
        // settings as dead entries the user can still toggle.
        val live = setOf(runningChannel, doneChannel, idleChannel, restAlarmChannel)
        for (retired in RETIRED_CHANNELS) {
            if (retired !in live) runCatching { service.deleteNotificationChannel(retired) }
        }

        service.createNotificationChannel(running)
        service.createNotificationChannel(done)
        service.createNotificationChannel(idle)
        service.createNotificationChannel(restAlarm)
        return moved
    }

    /**
     * What to say when nothing is running: when the forest last grew, and how
     * much of it grew today.
     *
     * [lastEndedAt] is null for someone who has never finished anything, which
     * needs different words — "0m since your last session" would be a strange
     * thing to tell a person on their first day. A rest counts towards it: it
     * is still time they spent here.
     */
    data class IdleSummary(val lastEndedAt: Long?, val todayMinutes: Int)

    /** The ongoing notification. Focus takes precedence when both are running. */
    fun buildOngoing(timer: ActiveTimer?, rest: RestTimer?, idle: IdleSummary?): Notification {
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
            .setDeleteIntent(restoreIntent())
            // Android 16 promotes a live, ongoing notification to a status bar
            // chip and a prominent lock screen slot — which is exactly what a
            // running timer is, and exactly what the sync-only one is not.
            // Asking for it unconditionally pinned a chip to the status bar
            // saying nothing, for as long as background sync was on. Older
            // versions ignore the request either way.
            .setRequestPromotedOngoing(timer != null || rest != null)

        if (timer != null) {
            val countdown = timer.mode == TimerMode.COUNTDOWN
            val state = if (countdown) {
                context.getString(
                    R.string.notif_focus_goal,
                    Time.formatMinutes(
                        timer.durationMinutes,
                        context.getString(R.string.unit_m),
                        context.getString(R.string.unit_h),
                    ),
                )
            } else {
                context.getString(R.string.notif_focus_stopwatch)
            }

            builder
                // Kept alongside the custom views, never instead of them: this is
                // what a car dashboard, a watch, or a lock screen that declines to
                // inflate someone else's layout falls back to reading.
                .setContentTitle(timer.title)
                .setContentText(
                    if (countdown) {
                        context.getString(
                            R.string.notif_focus_countdown,
                            Time.formatClock(timer.remainingSeconds()),
                        )
                    } else {
                        state
                    }
                )
                .setWhen(if (countdown) timer.endAt else timer.startedAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(countdown)

            builder.applyBigClock(
                title = timer.title,
                state = state,
                // The instant to count toward, or the one to count up from.
                target = if (countdown) timer.endAt else timer.startedAt,
                countDown = countdown,
                progress = if (countdown) timer.progress() else null,
            )

            // Finish is the only action: ending a countdown early is not a
            // different outcome any more, so "Give up" would do the same thing
            // under a word the app no longer means.
            builder.addAction(
                0,
                context.getString(R.string.notif_action_finish),
                serviceAction(TimerService.ACTION_FINISH),
            )
        } else if (rest != null) {
            val countdown = rest.isCountdown
            val state = if (countdown) {
                context.getString(
                    R.string.notif_rest_goal,
                    Time.formatMinutes(
                        rest.durationMinutes,
                        context.getString(R.string.unit_m),
                        context.getString(R.string.unit_h),
                    ),
                )
            } else {
                context.getString(R.string.notif_rest_text)
            }

            builder
                .setContentTitle(context.getString(R.string.notif_rest_title))
                .setContentText(
                    if (countdown) {
                        context.getString(
                            R.string.notif_rest_countdown,
                            Time.formatClock(rest.remainingSeconds()),
                        )
                    } else {
                        state
                    }
                )
                // A countdown counts toward the instant it lands on; the
                // stopwatch counts up from where it began.
                .setWhen(if (countdown) rest.endAt!! else rest.startedAt)
                .setUsesChronometer(true)
                .setChronometerCountDown(countdown)

            builder.applyBigClock(
                title = context.getString(R.string.notif_rest_title),
                state = state,
                target = if (countdown) rest.endAt!! else rest.startedAt,
                countDown = countdown,
                progress = if (countdown) rest.progress() else null,
            )

            builder.addAction(
                0,
                context.getString(R.string.notif_action_finish),
                serviceAction(TimerService.ACTION_FINISH_REST),
            )
        } else {
            // Nothing is running, but the service is. Rather than spend the most
            // frequently seen notification in the app on the word "syncing", it
            // counts up from the last session — the same chronometer the running
            // timer uses, pointed the other way.
            builder.setSubText(context.getString(R.string.notif_sync_subtext))
            applyIdleContent(builder, idle ?: IdleSummary(null, 0), worded = true)
        }

        return builder.build()
    }

    /**
     * Swaps the notification's content area for one that shows the time at a
     * size worth reading.
     *
     * The platform template files the elapsed time with the timestamp, in the
     * header, at header size — reasonable for a chat message and wrong for a
     * focus timer, where the clock is the entire content. There is no way to
     * resize that text, so the content area is replaced outright.
     *
     * Used by the running timer and by the idle notification alike: "how long
     * since you last focused" is a stopwatch too, and just as much the thing
     * worth reading from across a desk.
     *
     * [NotificationCompat.DecoratedCustomViewStyle] is what keeps this from
     * being a step backwards: the platform still draws the header, the app name,
     * the expander and the action buttons, so Finish and Give up look and behave
     * exactly as they did, and only the middle is ours. A fully custom
     * notification would have had to reimplement all of it, badly, and differently
     * on every OEM skin.
     *
     * The clock still ticks by itself. A RemoteViews Chronometer counts from
     * [android.os.SystemClock.elapsedRealtime], not the wall clock, so the
     * target instant is converted into that frame here. elapsedRealtime includes
     * time the device spent asleep, which is the property that matters: a phone
     * dozing in a pocket for an hour wakes with the clock still right.
     */
    private fun NotificationCompat.Builder.applyBigClock(
        title: String,
        state: String,
        target: Long,
        countDown: Boolean,
        progress: Float?,
    ) {
        // Converted at build time rather than stored: every repost recomputes it
        // against the clock as it is now, so drift cannot accumulate.
        val base = SystemClock.elapsedRealtime() + (target - System.currentTimeMillis())

        fun views(layout: Int, withState: Boolean) = RemoteViews(context.packageName, layout).apply {
            setTextViewText(R.id.notification_title, title)
            if (withState) setTextViewText(R.id.notification_state, state)
            setChronometer(R.id.notification_time, base, null, true)
            setChronometerCountDown(R.id.notification_time, countDown)
            if (progress == null) {
                // A stopwatch has no goal, so a bar would be measuring nothing.
                setViewVisibility(R.id.notification_progress, View.GONE)
            } else {
                setViewVisibility(R.id.notification_progress, View.VISIBLE)
                setProgressBar(R.id.notification_progress, 1000, (progress * 1000).toInt(), false)
            }
        }

        setStyle(NotificationCompat.DecoratedCustomViewStyle())
        setCustomContentView(views(R.layout.notification_timer, withState = false))
        setCustomBigContentView(views(R.layout.notification_timer_big, withState = true))
    }

    /**
     * The "nothing is growing" face, shared by the ongoing notification and the
     * standalone nudge so the two never disagree about how long it has been.
     *
     * The elapsed time is drawn by the platform's chronometer rather than
     * written into the text, which is the whole trick: it counts up on its own
     * from an instant handed to it once, so it stays exact for days with the app
     * asleep and the process gone. Nothing here is recorded — it is a clock on
     * the wall, not a session.
     *
     * [worded] adds the same figure to the body in plain language. Only the
     * caller that can repost — the running service — should ask for it, because
     * a sentence saying "20m" beside a chronometer reading 4:31:07 is worse than
     * no sentence at all.
     */
    private fun applyIdleContent(
        builder: NotificationCompat.Builder,
        idle: IdleSummary,
        worded: Boolean,
    ) {
        val since = idle.lastEndedAt
        val elapsed = if (since != null) System.currentTimeMillis() - since else -1L

        val today = if (idle.todayMinutes > 0) {
            context.getString(
                R.string.notif_idle_progress,
                Time.formatMinutes(
                    idle.todayMinutes,
                    context.getString(R.string.unit_m),
                    context.getString(R.string.unit_h),
                ),
            )
        } else {
            context.getString(R.string.notif_idle_none_today)
        }

        val never = since == null || elapsed < 0
        val sinceLine = if (never) {
            context.getString(R.string.notif_idle_never)
        } else {
            context.getString(R.string.notif_idle_since, gapLabel(elapsed))
        }

        // Unworded, the chronometer is already saying how long it has been, so
        // the body is spent on today's total instead — except on day one, when
        // there is no total and no clock, and the invitation is all there is.
        val body = if (worded) sinceLine else if (never) sinceLine else today
        val title = context.getString(R.string.notif_idle_title)

        builder
            .setContentTitle(title)
            .setContentText(body)
            .addAction(
                0,
                context.getString(R.string.notif_idle_action),
                openApp(MainActivity.DESTINATION_FOCUS),
            )

        // Under a day, the seconds still mean something — so the gap gets the
        // running timer's own clock, at the size that makes it readable from
        // across a desk rather than filed away as a timestamp in the header.
        if (since != null && elapsed in 0 until DAY_MS) {
            builder.setWhen(since).setShowWhen(true)
                .setUsesChronometer(true)
                .setChronometerCountDown(false)
            builder.applyBigClock(
                title = title,
                // The clock is a bare number without this: what it counts, and
                // what today already has in it.
                state = context.getString(R.string.notif_idle_clock_label) + " · " + todayShort(idle),
                target = since,
                countDown = false,
                progress = null,
            )
            return
        }

        // Past a day a ticking clock reads as a scolding, and "74:12:31" tells
        // nobody anything. The date of the last session does, so the plain
        // template — which can show one — is the better face.
        //
        // The choice is made here, when the notification is built. The ongoing
        // one is rebuilt every minute and so crosses over on time; the
        // standalone nudge is posted once and cannot be, so one posted at 23
        // hours keeps ticking until the app is next opened. A clock that is
        // merely blunt beats one that has quietly stopped being true.
        builder.setStyle(
            NotificationCompat.BigTextStyle()
                .bigText(if (worded && !never) "$sinceLine\n$today" else body)
        )
        if (since != null) {
            builder.setWhen(since).setShowWhen(true).setUsesChronometer(false)
        } else {
            builder.setShowWhen(false).setUsesChronometer(false)
        }
    }

    /** "2h 15m today", or an admission that there is nothing to report yet. */
    private fun todayShort(idle: IdleSummary): String =
        if (idle.todayMinutes > 0) {
            context.getString(
                R.string.notif_idle_today_short,
                Time.formatMinutes(
                    idle.todayMinutes,
                    context.getString(R.string.unit_m),
                    context.getString(R.string.unit_h),
                ),
            )
        } else {
            context.getString(R.string.notif_idle_today_none_short)
        }

    /**
     * "45m", "2h 15m", "3 days" — how long the forest has been still.
     *
     * The wording coarsens as the gap grows, because "74h 12m" is a number
     * nobody can feel, and precision stops being kind somewhere around bedtime.
     */
    private fun gapLabel(elapsedMs: Long): String {
        val days = (elapsedMs / DAY_MS).toInt()
        if (days >= 1) {
            return context.resources.getQuantityString(R.plurals.notif_idle_days, days, days)
        }
        return Time.formatMinutes(
            (elapsedMs / 60_000L).toInt(),
            context.getString(R.string.unit_m),
            context.getString(R.string.unit_h),
        )
    }

    /**
     * Fired when a session finishes, so it is noticed from the lock screen.
     *
     * It carries no delete intent, and that is the point. This announces
     * something that has already happened rather than reporting on something
     * still going on, so a swipe is the user saying they have seen it — and an
     * alert that reappeared from being dismissed would be one there is no way
     * to be rid of at all. Nothing is lost by letting it go: the tree was
     * planted before this was posted, and the forest is where it lives.
     */
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

        val notification = NotificationCompat.Builder(context, doneChannel)
            .setSmallIcon(R.drawable.ic_stat_tree)
            .setColor(ContextCompat.getColor(context, R.color.timber_accent))
            .setContentTitle(context.getString(R.string.notif_done_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            // Tapping it opens the forest, and clears it on the way.
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            // ALARM is the category Do Not Disturb lets through under its own
            // "allow alarms" rule, which is the default on every Android build.
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        runCatching { manager.notify(ID_DONE, notification) }
    }

    /**
     * The nudge shown while nothing is running and background sync is off.
     *
     * Deliberately low importance and silent: it is an invitation sitting in the
     * shade, not an alarm. It carries the same count-up clock as the ongoing
     * notification, but not the same sentence — with sync off there is no
     * service to repost it, so only the self-updating chronometer can be trusted
     * to still be true tomorrow morning.
     *
     * An invitation that could not be declined would stop being one, so this is
     * not restored when swiped either. It comes back the next time there is
     * something new to say — a session started or finished, or the app closed
     * again — and the switch in Settings is still what silences it for good.
     */
    fun showIdle(idle: IdleSummary) {
        if (!canPost()) return

        val builder = NotificationCompat.Builder(context, idleChannel)
            .setSmallIcon(R.drawable.ic_stat_tree)
            .setColor(ContextCompat.getColor(context, R.color.timber_accent))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        applyIdleContent(builder, idle, worded = false)

        runCatching { manager.notify(ID_IDLE, builder.build()) }
    }

    /**
     * The rest alarm: the one notification in this app that does not take no
     * for an answer.
     *
     * Four things separate it from every other alert here, and each is doing a
     * specific job:
     *
     * - **A full-screen intent.** On a locked or dark screen this is what turns
     *   the display on and puts the alarm in front of the user instead of in a
     *   drawer they have to know to open. It degrades to a heads-up banner by
     *   itself when the permission is not held, so it is always safe to ask.
     * - **Ongoing, with no auto-cancel.** The alarm outlives a tap on the body.
     * - **A delete intent that puts it straight back.** A swipe is how a
     *   notification is normally acknowledged, and here it must not be:
     *   dismissing this by reflex from a lock screen is exactly how a rest
     *   silently becomes an hour. Only the Dismiss action clears it.
     * - **`CATEGORY_ALARM`.** The class Do Not Disturb lets through under its
     *   own "allow alarms" rule, which is on by default on every Android build.
     *
     * [loud] is false once the ring has run its course, which softens the words
     * without withdrawing the notification — the message is still the message
     * when the noise has stopped.
     */
    fun showRestAlarm(durationMinutes: Int, loud: Boolean) {
        if (!canPost()) return

        val minutes = Time.formatMinutes(
            durationMinutes,
            context.getString(R.string.unit_m),
            context.getString(R.string.unit_h),
        )
        val text = context.getString(
            if (loud) R.string.notif_rest_alarm_text else R.string.notif_rest_alarm_text_quiet,
            minutes,
        )

        val builder = NotificationCompat.Builder(context, restAlarmChannel)
            .setSmallIcon(R.drawable.ic_stat_tree)
            .setColor(ContextCompat.getColor(context, R.color.timber_accent))
            .setContentTitle(context.getString(R.string.notif_rest_alarm_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(alarmActivity(fullScreen = false))
            .setOngoing(true)
            .setAutoCancel(false)
            .setDeleteIntent(restoreAlarmIntent())
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Counts up from the moment the rest ran out, so a user coming back
            // to it knows whether they lost two minutes or twenty.
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis())
            .addAction(
                0,
                context.getString(R.string.notif_action_rest_dismiss),
                serviceAction(TimerService.ACTION_DISMISS_REST_ALARM),
            )

        // Only while it is actually ringing. Asking for a full-screen takeover
        // after the noise has stopped would drag the user out of whatever they
        // are doing to tell them something that is no longer happening.
        if (loud && canUseFullScreen()) {
            builder.setFullScreenIntent(alarmActivity(fullScreen = true), true)
        }

        runCatching { manager.notify(ID_REST_ALARM, builder.build()) }
    }

    fun cancelRestAlarm() {
        runCatching { manager.cancel(ID_REST_ALARM) }
    }

    /**
     * Whether the platform will honour a full-screen intent.
     *
     * From Android 14 this is granted at install only to apps the store filed
     * as alarms or calling apps, and has to be asked for otherwise. A refusal
     * is not fatal — the notification still arrives as a heads-up banner — so
     * this only decides whether to ask for the takeover, never whether to
     * alarm at all.
     */
    fun canUseFullScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val service = context.getSystemService(NotificationManager::class.java) ?: return false
        return runCatching { service.canUseFullScreenIntent() }.getOrDefault(false)
    }

    private fun alarmActivity(fullScreen: Boolean): PendingIntent {
        val intent = Intent(context, RestAlarmActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            if (fullScreen) REQUEST_ALARM_FULL else REQUEST_ALARM_TAP,
            intent,
            IMMUTABLE,
        )
    }

    fun cancelIdle() {
        runCatching { manager.cancel(ID_IDLE) }
    }

    /** Taken as read once the user is looking at the app itself. */
    fun cancelCompleted() {
        runCatching { manager.cancel(ID_DONE) }
    }

    fun update(timer: ActiveTimer?, rest: RestTimer?, idle: IdleSummary?) {
        if (!canPost()) return
        runCatching { manager.notify(ID_RUNNING, buildOngoing(timer, rest, idle)) }
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

    /**
     * [destination] names a screen to land on. Extras are not part of a
     * PendingIntent's identity, so each destination needs its own request code
     * or FLAG_UPDATE_CURRENT would quietly rewrite the other one's target.
     */
    private fun openApp(destination: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (destination != null) intent.putExtra(MainActivity.EXTRA_DESTINATION, destination)
        return PendingIntent.getActivity(context, destination?.hashCode() ?: 0, intent, IMMUTABLE)
    }

    private fun serviceAction(action: String): PendingIntent {
        val intent = Intent(context, TimerService::class.java).setAction(action)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(context, action.hashCode(), intent, IMMUTABLE)
        } else {
            PendingIntent.getService(context, action.hashCode(), intent, IMMUTABLE)
        }
    }

    private fun restoreIntent(): PendingIntent {
        val intent = Intent(context, NotificationRestorer::class.java)
            .setAction(NotificationRestorer.ACTION_RESTORE)
        return PendingIntent.getBroadcast(context, REQUEST_RESTORE, intent, IMMUTABLE)
    }

    private fun restoreAlarmIntent(): PendingIntent {
        val intent = Intent(context, NotificationRestorer::class.java)
            .setAction(NotificationRestorer.ACTION_RESTORE_ALARM)
        return PendingIntent.getBroadcast(context, REQUEST_RESTORE_ALARM, intent, IMMUTABLE)
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
    private val restAlarmChannel: String get() = CHANNEL_REST_ALARM + generation

    companion object {
        const val CHANNEL_RUNNING = "timer-running-v2"
        const val CHANNEL_DONE = "timer-done"
        const val CHANNEL_IDLE = "timer-idle"
        const val CHANNEL_REST_ALARM = "rest-alarm"

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
            CHANNEL_REST_ALARM,
            CHANNEL_RUNNING + DND_SUFFIX,
            CHANNEL_DONE + DND_SUFFIX,
            CHANNEL_IDLE + DND_SUFFIX,
            CHANNEL_REST_ALARM + DND_SUFFIX,
        )

        const val ID_RUNNING = 1001
        const val ID_DONE = 1002
        const val ID_IDLE = 1003
        const val ID_REST_ALARM = 1004

        /**
         * Request codes for the delete intents. [REQUEST_RESTORE] is kept at the
         * value the ongoing notification has always used, so an update does not
         * orphan a token already sitting on a posted notification.
         */
        private const val REQUEST_RESTORE = 1
        private const val REQUEST_RESTORE_ALARM = 2

        /**
         * The alarm activity is reached two ways and they must stay distinct:
         * extras are not part of a PendingIntent's identity, but the request
         * code is, and a full-screen intent sharing a token with a content
         * intent would have FLAG_UPDATE_CURRENT quietly rewrite one of them.
         */
        private const val REQUEST_ALARM_FULL = 3
        private const val REQUEST_ALARM_TAP = 4

        /** Where a ticking clock stops being encouragement and starts nagging. */
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        private const val PREFS = "timber-notifications"
        private const val KEY_DND_CHANNELS = "dnd-channels"

        private const val IMMUTABLE =
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    }
}
