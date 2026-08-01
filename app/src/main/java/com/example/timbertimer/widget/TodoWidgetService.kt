package com.example.timbertimer.widget

import android.content.Context
import android.content.Intent
import android.text.SpannableString
import android.text.style.StrikethroughSpan
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import androidx.core.content.ContextCompat
import com.example.timbertimer.R
import com.example.timbertimer.data.local.LocalStore
import com.example.timbertimer.data.local.WidgetNote

/** Supplies the widget's scrolling list of tasks. */
class TodoWidgetService : RemoteViewsService() {

    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        TodoWidgetFactory(applicationContext)
}

private class TodoWidgetFactory(private val context: Context) :
    RemoteViewsService.RemoteViewsFactory {

    private val store = LocalStore(context)

    /**
     * Unfinished tasks first, matching the app's own list. Held in a field
     * because the launcher reads [getViewAt] many times per refresh and re-sorting
     * on each call would be both slower and — if a toggle landed midway — able to
     * shuffle rows out from under the read.
     */
    private var notes: List<WidgetNote> = emptyList()

    override fun onCreate() = Unit

    override fun onDataSetChanged() {
        val snapshot = store.readWidgetNotes()
        notes = snapshot.filterNot { it.done } + snapshot.filter { it.done }
    }

    override fun onDestroy() {
        notes = emptyList()
    }

    override fun getCount(): Int = notes.size

    override fun getItemId(position: Int): Long =
        notes.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()

    /** Stable, so toggling a task does not make the whole list flicker. */
    override fun hasStableIds(): Boolean = true

    override fun getViewTypeCount(): Int = 1

    override fun getLoadingView(): RemoteViews? = null

    override fun getViewAt(position: Int): RemoteViews {
        val note = notes.getOrNull(position)
            ?: return RemoteViews(context.packageName, R.layout.widget_todo_item)

        return RemoteViews(context.packageName, R.layout.widget_todo_item).apply {
            setImageViewResource(
                R.id.item_check,
                if (note.done) R.drawable.ic_widget_checked else R.drawable.ic_widget_unchecked,
            )

            // Struck through via a span rather than a paint flag: RemoteViews can
            // carry a styled CharSequence, but it cannot call setPaintFlags.
            val text: CharSequence = if (note.done) {
                SpannableString(note.text).apply {
                    setSpan(StrikethroughSpan(), 0, length, SpannableString.SPAN_INCLUSIVE_EXCLUSIVE)
                }
            } else {
                note.text
            }
            setTextViewText(R.id.item_text, text)
            setTextColor(
                R.id.item_text,
                ContextCompat.getColor(
                    context,
                    if (note.done) R.color.widget_muted else R.color.widget_text,
                ),
            )
            setContentDescription(
                R.id.item_check,
                context.getString(
                    if (note.done) R.string.notes_mark_incomplete else R.string.notes_mark_complete
                ),
            )

            // The circle crosses the task off in place; the text opens the app to
            // edit it. Both merge into the collection's one broadcast template.
            setOnClickFillInIntent(
                R.id.item_check,
                Intent()
                    .setAction(TodoWidget.ACTION_TOGGLE)
                    .putExtra(TodoWidget.EXTRA_NOTE_ID, note.id),
            )
            setOnClickFillInIntent(
                R.id.item_text,
                Intent().setAction(TodoWidget.ACTION_OPEN),
            )
        }
    }
}
