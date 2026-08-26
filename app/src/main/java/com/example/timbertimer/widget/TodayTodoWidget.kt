package com.example.timbertimer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.timbertimer.MainActivity
import com.example.timbertimer.R
import com.example.timbertimer.TimberApplication
import com.example.timbertimer.data.local.LocalStore
import kotlinx.coroutines.launch

/**
 * The home screen's Today widget: the same reading-plus-toggle surface as
 * [TodoWidget], but scoped to today's list alone — starting blank on a new
 * day exactly as the in-app Today list does (see [TodayTodoWidgetService]),
 * with nothing to browse back through here; that's what opening the app is
 * for.
 *
 * A near-identical twin of [TodoWidget] rather than a shared base class: a
 * classic [AppWidgetProvider] is mostly wiring (its own manifest entries, its
 * own request codes, its own broadcast actions so the two widgets' taps can
 * never cross), and the duplication is smaller than the abstraction would be.
 */
class TodayTodoWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    /**
     * The day turning over is the one change this widget has to notice that
     * nothing in the data announces: yesterday's list is not today's, yet not
     * a single note differs. The app's own clock cannot be relied on to say
     * so — it stops ticking when no timer runs and no screen is open, which
     * is exactly the state a phone is in at midnight — and this widget is
     * deliberately not on a polling schedule. So the system's own
     * date-changed broadcast is what wakes it, which is what that broadcast
     * is for.
     */
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> refresh(context)
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.timbertimer.widget.TODAY_TOGGLE"
        const val ACTION_OPEN = "com.example.timbertimer.widget.TODAY_OPEN"
        const val EXTRA_NOTE_ID = "note_id"

        /** Redraws every placed widget: the header counts and the list itself. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, TodayTodoWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return

            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
            ids.forEach { id -> render(context, manager, id) }
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val notes = TodayTodoWidgetService.todaysNotes(context)
            val done = notes.count { it.done }

            val views = RemoteViews(context.packageName, R.layout.widget_todo).apply {
                // Same layout as the general list's widget; only the header and
                // empty text read differently, set here rather than baked into a
                // second copy of the XML.
                setTextViewText(R.id.widget_title, context.getString(R.string.notes_today_title))
                setTextViewText(
                    R.id.widget_count,
                    if (notes.isEmpty()) "" else context.getString(R.string.widget_count, done, notes.size),
                )
                setTextViewText(R.id.widget_empty, context.getString(R.string.widget_today_empty))

                // The adapter runs in TodayTodoWidgetService; the id in the URI is
                // what keeps two placed widgets from sharing one factory instance.
                val adapterIntent = Intent(context, TodayTodoWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                setRemoteAdapter(R.id.widget_list, adapterIntent)
                setEmptyView(R.id.widget_list, R.id.widget_empty)

                // One template for every row; each row's fill-in intent says
                // whether it meant "cross this off" or "open this to edit".
                setPendingIntentTemplate(R.id.widget_list, rowTemplate(context))

                setOnClickPendingIntent(R.id.widget_add, openIntent(context, REQUEST_ADD))
                setOnClickPendingIntent(R.id.widget_header, openIntent(context, REQUEST_HEADER))
                setOnClickPendingIntent(R.id.widget_empty, openIntent(context, REQUEST_EMPTY))
            }

            runCatching { manager.updateAppWidget(widgetId, views) }
        }

        private fun rowTemplate(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            REQUEST_TEMPLATE,
            // Pinned to the private action receiver, not to this provider: this
            // one has to be exported for the system to enumerate it as a widget,
            // and a row tap must not be something another app can forge.
            Intent(context, TodayTodoWidgetActions::class.java),
            // Mutable so the row's fill-in intent can supply the action and id.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        /**
         * The header, the + and the empty state launch the activity directly.
         * Only a tap on a row's text has to travel through the broadcast, because
         * a collection gets exactly one template and that one is already spoken
         * for by the toggle.
         */
        private fun openIntent(context: Context, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_TASKS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        // Distinct from TodoWidget's request codes: two PendingIntents that
        // differ only by request code but not by extras can otherwise collide.
        private const val REQUEST_TEMPLATE = 910
        private const val REQUEST_ADD = 911
        private const val REQUEST_HEADER = 912
        private const val REQUEST_EMPTY = 913
    }
}

/**
 * Handles the two things a row tap can mean, for the Today widget.
 *
 * Separate from [TodayTodoWidget] because that one must be exported so the
 * launcher can offer the widget at all, and these actions change the user's
 * data. This receiver is private to the app; only its own PendingIntents
 * reach it.
 */
class TodayTodoWidgetActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TodayTodoWidget.ACTION_TOGGLE -> {
                val noteId = intent.getStringExtra(TodayTodoWidget.EXTRA_NOTE_ID) ?: return
                val container =
                    (context.applicationContext as? TimberApplication)?.container ?: return
                val pending = goAsync()
                container.scope.launch {
                    try {
                        // This process may have been started by the tap itself,
                        // so the account and its list are resolved first.
                        container.repository.toggleNoteFromWidget(noteId)
                    } finally {
                        TodayTodoWidget.refresh(context)
                        pending.finish()
                    }
                }
            }

            TodayTodoWidget.ACTION_OPEN -> {
                val intent = Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_TASKS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                runCatching { context.startActivity(intent) }
            }
        }
    }
}
