package com.example.timbertimer.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import com.example.timbertimer.data.local.TodayTotals

/**
 * The ring the time-by-project widget draws: one arc per project, sized to its
 * share of the day.
 *
 * A bitmap rather than a drawable, because RemoteViews cannot build a shape at
 * runtime — it can only carry views a layout already declared, plus images. So
 * the ring is painted here, in the app's own process, and shipped across as a
 * picture.
 *
 * Stroked rather than filled: a pie would need its middle painted the same
 * colour as whatever is behind it, which is a promise this cannot keep once the
 * user's wallpaper is involved. A ring leaves its middle genuinely transparent
 * and looks right on anything.
 */
object ProjectTimeChart {

    /**
     * Bitmaps in a RemoteViews travel over Binder, which has a hard ceiling on
     * how much a single transaction may carry — and the widget's other views
     * share that budget. Even the largest ring here is ~230 KB, well inside it,
     * and drawing bigger would only buy detail nobody can see at this size.
     */
    private const val MAX_PX = 240

    /** Below this there is not enough ring left to read; see [render]. */
    private const val MIN_PX = 48

    /** As a share of the diameter. Thick enough to read, thin enough to be a ring. */
    private const val STROKE_RATIO = 0.30f

    /**
     * Paints the day as a ring, or returns null when there is nothing to draw.
     *
     * Null rather than an empty circle: a day with no records yet has no shape,
     * and a grey ring would look like a project the user does not have. The
     * caller hides the view instead.
     */
    fun render(context: Context, totals: TodayTotals, sizeDp: Int): Bitmap? {
        val total = totals.minutes
        // Both guards matter. No projects is the ordinary empty day; a total of
        // zero is the odd one — records exist but every one of them rounds to
        // nothing — and it is also the division below, so it cannot be assumed
        // away.
        if (total <= 0 || totals.projects.isEmpty()) return null

        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).toInt().coerceIn(MIN_PX, MAX_PX)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val stroke = size * STROKE_RATIO
        // Half the stroke keeps the arc inside the bitmap — a stroke straddles
        // the path it follows, so a ring drawn to the edge loses its outer half.
        val inset = stroke / 2f
        val box = RectF(inset, inset, size - inset, size - inset)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            // Butt, not round: round caps overhang their arc by half a stroke
            // each, so neighbouring slices would overlap and the smallest ones
            // would be drawn over entirely.
            strokeCap = Paint.Cap.BUTT
        }

        // Twelve o'clock, clockwise — where a clock face starts, which is what
        // a day's worth of time reads as.
        val start = -90f
        val end = start + 360f
        var angle = start

        totals.projects.forEachIndexed { index, project ->
            // The last slice takes whatever is left rather than its own
            // computed share. Sixteen divisions each rounded to a float leave a
            // seam otherwise — a hairline of background showing through a ring
            // that is supposed to be closed.
            val sweep =
                if (index == totals.projects.lastIndex) end - angle
                else 360f * project.minutes / total

            if (sweep > 0f) {
                paint.color = ProjectTimeWidgetService.colorOf(project)
                canvas.drawArc(box, angle, sweep, false, paint)
            }
            angle += sweep
        }

        return bitmap
    }

    /**
     * What a screen reader says instead of the ring — the shape carries real
     * information, so it cannot be left as decoration.
     *
     * Only the largest few: reading out sixteen projects is not a summary.
     */
    fun describe(context: Context, totals: TodayTotals): String =
        totals.projects.take(3).joinToString(", ") { project ->
            "${ProjectTimeWidgetService.displayName(context, project)} " +
                ProjectTimeWidget.formatMinutes(context, project.minutes)
        }
}
