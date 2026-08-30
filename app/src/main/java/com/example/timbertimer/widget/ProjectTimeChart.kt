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
     * Bitmaps in a RemoteViews are parcelled as blobs — shared memory once they
     * pass a few kilobytes — so they do not spend the Binder transaction
     * budget, and the platform's own ceiling for a widget is generous (six
     * bytes per screen pixel). This cap is well under both while still leaving
     * the largest ring crisp on a 3x density screen; past it there is only
     * detail nobody can see.
     */
    private const val MAX_PX = 480

    /** Below this there is not enough ring left to read; see [render]. */
    private const val MIN_PX = 48

    // How thick the ring is, as a share of its diameter.
    //
    // Not one number, because thickness does not read the same at every size: a
    // small ring needs a fat stroke to be legible at all, while the same share
    // on a large one closes the middle up and turns the ring into a blob. So it
    // tapers — chunky when small, slimmer as it grows.
    private const val THICK_AT_DP = 64
    private const val THIN_AT_DP = 128
    private const val STROKE_RATIO_THICK = 0.30f
    private const val STROKE_RATIO_THIN = 0.21f

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

        val metrics = context.resources.displayMetrics
        val size = (sizeDp * metrics.density).toInt().coerceIn(MIN_PX, MAX_PX)

        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        // Stamped with the density in force now, not the one the device booted
        // with, which is what a new bitmap would otherwise carry. They differ
        // whenever the user has moved the display-size slider, and the widget
        // sizes this image from its own intrinsic width on hosts too old for
        // setViewLayoutWidth — so a stale density would quietly scale the ring.
        bitmap.density = metrics.densityDpi
        val canvas = Canvas(bitmap)

        val stroke = size * strokeRatio(sizeDp)
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

    /** Thickness for a ring of this diameter — see the constants above. */
    private fun strokeRatio(sizeDp: Int): Float {
        val travelled = (sizeDp - THICK_AT_DP).toFloat() / (THIN_AT_DP - THICK_AT_DP)
        val t = travelled.coerceIn(0f, 1f)
        return STROKE_RATIO_THICK + (STROKE_RATIO_THIN - STROKE_RATIO_THICK) * t
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
