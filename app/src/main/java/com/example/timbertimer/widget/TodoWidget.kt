package com.example.timbertimer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.example.timbertimer.MainActivity
import com.example.timbertimer.R
import com.example.timbertimer.TimberApplication
import com.example.timbertimer.data.local.LocalStore
import android.content.BroadcastReceiver
import kotlinx.coroutines.launch

/**
 * The home screen to-do widget.
 *
 * It is deliberately a *reading* surface with one exception: crossing a task off
 * is the single action worth doing without unlocking into an app, so a tap on
 * the circle toggles in place. Everything that needs a keyboard or a drag —
 * adding, editing, reordering — opens the app on the To-Do tab instead of trying
 * to reproduce those in RemoteViews, where they would be worse.
 */
class TodoWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.timbertimer.widget.TOGGLE"
        const val ACTION_OPEN = "com.example.timbertimer.widget.OPEN"
        const val EXTRA_NOTE_ID = "note_id"

        /** Redraws every placed widget: the header counts and the list itself. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, TodoWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return

            // Push the views first, then ask for the reload — see the note on
            // TodayTodoWidget.refresh. Asking first lets the push discard the
            // request, which leaves the list showing rows it already had.
            ids.forEach { id -> render(context, manager, id) }
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val notes = LocalStore(context).readWidgetNotes()
            val done = notes.count { it.done }

            val views = RemoteViews(context.packageName, R.layout.widget_todo).apply {
                setTextViewText(
                    R.id.widget_count,
                    if (notes.isEmpty()) "" else context.getString(R.string.widget_count, done, notes.size),
                )

                // The adapter runs in TodoWidgetService; the id in the URI is what
                // keeps two placed widgets from sharing one factory instance.
                val adapterIntent = Intent(context, TodoWidgetService::class.java).apply {
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
            Intent(context, TodoWidgetActions::class.java),
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

        private const val REQUEST_TEMPLATE = 900
        private const val REQUEST_ADD = 901
        private const val REQUEST_HEADER = 902
        private const val REQUEST_EMPTY = 903
    }
}

/**
 * Handles the two things a row tap can mean.
 *
 * Separate from [TodoWidget] because that one must be exported so the launcher
 * can offer the widget at all, and these actions change the user's data. This
 * receiver is private to the app; only its own PendingIntents reach it.
 */
class TodoWidgetActions : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TodoWidget.ACTION_TOGGLE -> {
                val noteId = intent.getStringExtra(TodoWidget.EXTRA_NOTE_ID) ?: return
                val container =
                    (context.applicationContext as? TimberApplication)?.container ?: return
                val pending = goAsync()
                container.scope.launch {
                    try {
                        // This process may have been started by the tap itself,
                        // so the account and its list are resolved first.
                        container.repository.toggleNoteFromWidget(noteId)
                    } finally {
                        TodoWidget.refresh(context)
                        pending.finish()
                    }
                }
            }

            TodoWidget.ACTION_OPEN -> {
                val intent = Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_TASKS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                runCatching { context.startActivity(intent) }
            }
        }
    }
}
