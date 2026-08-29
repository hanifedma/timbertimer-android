package com.example.timbertimer.core

import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.FocusRecord
import kotlin.math.roundToInt

/**
 * How a day's records are laid out on the calendar grid.
 *
 * Deliberately free of any Compose or Android import: this is where the tricky
 * parts live — a session that runs past midnight, and two that overlap — and
 * both are far easier to pin down in a plain JVM test than by dragging blocks
 * around on a device.
 */

/** The shortest block the grid will draw, and the smallest drag it will accept. */
const val CALENDAR_MIN_MINUTES = 5

/** One record (or the running timer) as it appears on one day column. */
data class CalendarSegment(
    val record: FocusRecord?,
    val projectId: String,
    val title: String,
    val running: Boolean,
    val dayIndex: Int,
    val startMillis: Long,
    val endMillis: Long,
    val startMin: Float,
    val endMin: Float,
    val minutes: Int,
    /**
     * True when this piece begins at midnight because the record began on an
     * earlier day — so the top edge drawn here is the calendar's own boundary,
     * not the moment the session started.
     */
    val clippedStart: Boolean,
    /** The same at the other end: the record runs on past this midnight. */
    val clippedEnd: Boolean,
    val column: Int,
    val columns: Int,
) {
    /**
     * True for a piece that is missing at least one of its real ends.
     *
     * Worth keeping as a name of its own because it answers "can this block be
     * moved as a whole?", which is a different question from "is this edge
     * real?" — a record across midnight has two genuine ends, they just live on
     * different days, and each of them is editable from the day it falls on.
     */
    val partial: Boolean get() = clippedStart || clippedEnd
}

/**
 * Splits every record into per-day pieces — a session across midnight shows in
 * both columns — then lays overlapping pieces out side by side.
 */
fun buildSegments(
    records: List<FocusRecord>,
    timer: ActiveTimer?,
    dayStarts: List<Long>,
    now: Long,
): List<CalendarSegment> {
    if (dayStarts.isEmpty()) return emptyList()
    val rangeStart = dayStarts.first()
    val rangeEnd = Time.addDays(dayStarts.last(), 1)

    data class Entry(
        val record: FocusRecord?,
        val projectId: String,
        val title: String,
        val running: Boolean,
        val start: Long,
        val end: Long,
    )

    val entries = buildList {
        records.forEach { record ->
            add(
                Entry(
                    record = record,
                    projectId = record.projectId,
                    title = record.title,
                    running = false,
                    start = record.startedAt,
                    end = maxOf(record.startedAt, record.endedAt),
                )
            )
        }
        if (timer != null) {
            add(
                Entry(
                    record = null,
                    projectId = timer.projectId,
                    title = timer.title,
                    running = true,
                    start = timer.startedAt,
                    end = maxOf(timer.startedAt, now),
                )
            )
        }
    }.filter { it.end > rangeStart && it.start < rangeEnd }

    val result = mutableListOf<CalendarSegment>()

    dayStarts.forEachIndexed { index, dayStart ->
        val dayEnd = Time.addDays(dayStart, 1)
        val perDay = mutableListOf<CalendarSegment>()

        entries.forEach { entry ->
            val from = maxOf(entry.start, dayStart)
            val to = minOf(entry.end, dayEnd)
            val startsHere = entry.start >= dayStart && entry.start < dayEnd
            if (to <= from && !startsHere) return@forEach

            val startMin = ((from - dayStart) / 60_000f).coerceIn(0f, 1440f)
            val rawEndMin = ((to - dayStart) / 60_000f).coerceIn(startMin, 1440f)
            perDay += CalendarSegment(
                record = entry.record,
                projectId = entry.projectId,
                title = entry.title,
                running = entry.running,
                dayIndex = index,
                startMillis = from,
                endMillis = maxOf(to, from),
                startMin = startMin,
                // Very short records still need a tappable block.
                endMin = minOf(1440f, maxOf(rawEndMin, startMin + CALENDAR_MIN_MINUTES)),
                minutes = (((minOf(entry.end, dayEnd) - from) / 60_000f).roundToInt()).coerceAtLeast(0),
                clippedStart = entry.start < dayStart,
                clippedEnd = entry.end > dayEnd,
                column = 0,
                columns = 1,
            )
        }

        result += pack(perDay)
    }

    return result
}

/**
 * Greedy column packing: within each run of overlapping blocks, each one takes
 * the first column that is free, and they share the width of that run.
 */
internal fun pack(segments: MutableList<CalendarSegment>): List<CalendarSegment> {
    if (segments.isEmpty()) return segments
    segments.sortWith(compareBy({ it.startMin }, { -it.endMin }))

    val out = mutableListOf<CalendarSegment>()
    var cluster = mutableListOf<CalendarSegment>()
    var clusterEnd = Float.NEGATIVE_INFINITY

    fun flush() {
        if (cluster.isEmpty()) return
        val columnEnds = mutableListOf<Float>()
        val assigned = cluster.map { segment ->
            var column = columnEnds.indexOfFirst { it <= segment.startMin }
            if (column == -1) {
                columnEnds += segment.endMin
                column = columnEnds.lastIndex
            } else {
                columnEnds[column] = segment.endMin
            }
            segment to column
        }
        assigned.forEach { (segment, column) ->
            out += segment.copy(column = column, columns = columnEnds.size)
        }
        cluster = mutableListOf()
        clusterEnd = Float.NEGATIVE_INFINITY
    }

    segments.forEach { segment ->
        if (cluster.isNotEmpty() && segment.startMin >= clusterEnd) flush()
        cluster += segment
        clusterEnd = maxOf(clusterEnd, segment.endMin)
    }
    flush()

    return out
}
