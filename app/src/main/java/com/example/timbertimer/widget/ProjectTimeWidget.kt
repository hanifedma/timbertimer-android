package com.example.timbertimer.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import com.example.timbertimer.MainActivity
import com.example.timbertimer.R
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.local.LocalStore
import com.example.timbertimer.data.local.TodayTotals

/**
 * The home screen's time-by-project widget: how long today went on each
 * project, longest first, with the day's total in the header.
 *
 * Read-only, which is what separates it from the to-do widgets. There is
 * nothing here a tap could sensibly change — a total is an outcome, not a
 * setting — so the whole surface simply opens the app, and the list needs no
 * per-row intents at all.
 *
 * The figures come from a snapshot written by
 * [com.example.timbertimer.AppContainer], not from the repository: the
 * launcher can start this process on its own, with no account resolved and no
 * records loaded. See [ProjectTimeWidgetService.todaysTotals].
 */
class ProjectTimeWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        appWidgetIds.forEach { id -> render(context, appWidgetManager, id) }
    }

    /**
     * Resizing changes whether the ring fits, and nothing else would notice.
     *
     * Without this a widget dragged wider keeps the list-only layout it was
     * given when it was narrow, until some unrelated change happens to redraw
     * it — which reads as the ring being broken rather than absent by design.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle?,
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        render(context, appWidgetManager, appWidgetId)
    }

    companion object {

        /** Redraws every placed widget: the header total and the list itself. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, ProjectTimeWidget::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return

            // Push the views first, then ask for the reload — see the note on
            // TodayTodoWidget.refresh. Asking first lets the push discard the
            // request, which leaves the list showing rows it already had.
            ids.forEach { id -> render(context, manager, id) }
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        private fun render(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val totals = ProjectTimeWidgetService.todaysTotals(context)

            val views = RemoteViews(context.packageName, R.layout.widget_project_time).apply {
                setTextViewText(
                    R.id.widget_total,
                    if (totals.projects.isEmpty()) "" else formatMinutes(context, totals.minutes),
                )

                // The id in the URI is what keeps two placed widgets from
                // sharing one factory instance.
                val adapterIntent = Intent(context, ProjectTimeWidgetService::class.java).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                    data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
                }
                setRemoteAdapter(R.id.widget_list, adapterIntent)
                setEmptyView(R.id.widget_list, R.id.widget_empty)

                // The ring, and the decision not to draw one.
                //
                // Two separate reasons it may be absent, and both have to be
                // handled or the widget lies: there is nothing to chart yet, or
                // this widget is too narrow to hold a ring and a legible list
                // side by side. On a 2-cell home screen the list is the part
                // worth keeping — a ring with no labels beside it says which
                // project won and nothing else.
                val chart =
                    if (widthDp(manager, widgetId) >= CHART_MIN_WIDTH_DP) {
                        ProjectTimeChart.render(context, totals, CHART_SIZE_DP)
                    } else null

                if (chart == null) {
                    setViewVisibility(R.id.widget_chart, View.GONE)
                } else {
                    setViewVisibility(R.id.widget_chart, View.VISIBLE)
                    setImageViewBitmap(R.id.widget_chart, chart)
                    // The ring carries real information, so it is not
                    // decoration and cannot be left unlabelled.
                    setContentDescription(
                        R.id.widget_chart,
                        ProjectTimeChart.describe(context, totals),
                    )
                }

                // A collection swallows taps on its rows unless a template is
                // set, so one is set even though every row means the same
                // thing — without it, tapping a row would do nothing at all
                // while the header beside it opened the app.
                setPendingIntentTemplate(R.id.widget_list, rowTemplate(context))
                setOnClickPendingIntent(R.id.widget_header, openIntent(context, REQUEST_HEADER))
                setOnClickPendingIntent(R.id.widget_empty, openIntent(context, REQUEST_EMPTY))
            }

            runCatching { manager.updateAppWidget(widgetId, views) }
        }

        /** "1h 20m", in the units the rest of the app uses. */
        fun formatMinutes(context: Context, minutes: Int): String = Time.formatMinutes(
            minutes,
            context.getString(R.string.unit_m),
            context.getString(R.string.unit_h),
        )

        /**
         * The one template every row shares.
         *
         * Mutable, which a collection's template has to be: the launcher merges
         * each row's fill-in intent into it on click, and an immutable one
         * cannot be merged into — the tap is simply swallowed. Nothing is
         * actually filled in here, because every row means the same thing, but
         * the flag is what makes the tap arrive at all.
         */
        private fun rowTemplate(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_TEMPLATE,
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_RECORDS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )

        private fun openIntent(context: Context, requestCode: Int): PendingIntent =
            PendingIntent.getActivity(
                context,
                requestCode,
                Intent(context, MainActivity::class.java)
                    .setAction(Intent.ACTION_MAIN)
                    .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_RECORDS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        /**
         * How wide the host says this widget currently is, in dp.
         *
         * Falls back to a width that keeps the ring when the host will not say
         * — some launchers report nothing until the first resize, and the
         * common case is a widget placed at its default size, which is wide
         * enough. Better to show the ring and have it disappear on a shrink
         * than to hide it on every launcher that stays quiet.
         */
        private fun widthDp(manager: AppWidgetManager, widgetId: Int): Int {
            val options = runCatching { manager.getAppWidgetOptions(widgetId) }.getOrNull()
                ?: return CHART_MIN_WIDTH_DP
            val min = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
            return if (min > 0) min else CHART_MIN_WIDTH_DP
        }

        /** Matches the ImageView in widget_project_time.xml. */
        private const val CHART_SIZE_DP = 64

        /**
         * Below this the ring and the list start fighting for the same room.
         * The widget's own minResizeWidth is 110dp, so this is reachable.
         */
        private const val CHART_MIN_WIDTH_DP = 180

        // Distinct from the to-do widgets': two PendingIntents that differ only
        // by request code but not by extras can otherwise collide.
        private const val REQUEST_TEMPLATE = 920
        private const val REQUEST_HEADER = 921
        private const val REQUEST_EMPTY = 922
    }
}

/**
 * Midnight, which nothing in the data announces.
 *
 * Yesterday's totals are not today's, yet not a single record differs — and the
 * app's own clock cannot be relied on to notice, because it stops ticking when
 * nothing is running and no screen is open, which is exactly the state a phone
 * is in at 00:00. The system's date-changed broadcast is what wakes this.
 *
 * Nothing is recomputed here. The stored snapshot carries the day it belongs to
 * ([TodayTotals.dateKey]), so simply redrawing against the new date is enough to
 * empty the widget and reset the count — see [TodayTotals.forDay].
 */
class DayRolloverReceiver : android.content.BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            -> {
                // TodayTodoWidget hears these itself; this owns the two
                // surfaces that have no other way to learn the day moved.
                ProjectTimeWidget.refresh(context)
                repostRestTally(context)
            }
        }
    }

    /**
     * The standing rest count starts a new day at zero.
     *
     * Posted straight from the snapshot rather than through the engine, because
     * this broadcast may be the only thing running — and the switch is checked
     * first, since a receiver that fires regardless must not resurrect a
     * notification the user has turned off.
     */
    private fun repostRestTally(context: Context) {
        val app = context.applicationContext as? com.example.timbertimer.TimberApplication ?: return
        if (!app.container.settings.restTally.value) return
        val today = Time.localDateKey(System.currentTimeMillis())
        val totals = LocalStore(context).readTodayTotals().forDay(today)
        app.container.notifications.showRestTally(totals.rests, totals.restMinutes)
    }
}
