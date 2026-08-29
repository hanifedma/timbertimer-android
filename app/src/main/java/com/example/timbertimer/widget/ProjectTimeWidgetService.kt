package com.example.timbertimer.widget

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.timbertimer.R
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.local.LocalStore
import com.example.timbertimer.data.local.TodayTotals
import com.example.timbertimer.data.local.WidgetProjectTotal
import com.example.timbertimer.data.model.Projects

/** Supplies the time-by-project widget's list of projects. */
class ProjectTimeWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        ProjectTimeFactory(applicationContext)

    companion object {
        /**
         * Today's totals, checked against the clock rather than trusted whole.
         *
         * The snapshot names the day it was written for, so one left behind by
         * yesterday reads as empty instead of being served up as today's — see
         * [TodayTotals.forDay]. That matters most in exactly the case nothing
         * else covers: a phone that sat untouched through midnight, where no
         * record changed and so nothing prompted a rewrite.
         */
        fun todaysTotals(context: Context): TodayTotals {
            val today = Time.localDateKey(System.currentTimeMillis())
            return LocalStore(context).readTodayTotals().forDay(today)
        }

        /**
         * The name to show for a project.
         *
         * Built-in projects are stored in English so a record means the same on
         * every device; the translation belongs at the point of display, which
         * is here. A name the user typed is their own words and is left alone.
         *
         * This mirrors `projectLabel` in the app's Compose UI, which cannot be
         * reused because it is a @Composable.
         */
        fun displayName(context: Context, total: WidgetProjectTotal): String = when {
            total.id == Projects.REST_ID && total.name == Projects.REST_NAME ->
                context.getString(R.string.project_rest_name)

            total.id == Projects.DEFAULT_ID && total.name == Projects.DEFAULT_NAME ->
                context.getString(R.string.project_default_name)

            total.name.isBlank() -> context.getString(R.string.project_none)
            else -> total.name
        }

        /** A project's `#rrggbb`, or the neutral grey if it cannot be parsed. */
        fun colorOf(total: WidgetProjectTotal): Int =
            runCatching { Color.parseColor(total.color) }
                .getOrElse { Color.parseColor(Projects.MISSING_COLOR) }
    }
}

private class ProjectTimeFactory(private val context: Context) :
    RemoteViewsService.RemoteViewsFactory {

    /**
     * Held in a field because the launcher calls [getViewAt] many times per
     * refresh; re-reading the snapshot on each call would be slower and — if a
     * session finished midway — able to shuffle rows out from under the read.
     */
    private var rows: List<WidgetProjectTotal> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        rows = ProjectTimeWidgetService.todaysTotals(context).projects
    }

    override fun onDestroy() {
        rows = emptyList()
    }

    override fun getCount(): Int = rows.size

    override fun getItemId(position: Int): Long =
        rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    /** Stable, so a total ticking up does not make the whole list flicker. */
    override fun hasStableIds(): Boolean = true

    override fun getViewTypeCount(): Int = 1

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val total = rows.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_project_time_item)

        return RemoteViews(context.packageName, R.layout.widget_project_time_item).apply {
            val name = ProjectTimeWidgetService.displayName(context, total)
            setTextViewText(R.id.item_name, name)
            setTextViewText(
                R.id.item_minutes,
                ProjectTimeWidget.formatMinutes(context, total.minutes),
            )

            // The disc is drawn white and tinted here: RemoteViews cannot build
            // a shape per row, so one drawable serves every project colour.
            setInt(R.id.item_dot, "setColorFilter", ProjectTimeWidgetService.colorOf(total))

            setContentDescription(
                R.id.item_dot,
                name,
            )

            // Every row means "open the app", but a collection's rows are inert
            // without a fill-in intent, so each carries an empty one that the
            // template then supplies the destination for.
            setOnClickFillInIntent(R.id.item_name, Intent())
            setOnClickFillInIntent(R.id.item_minutes, Intent())
        }
    }
}
