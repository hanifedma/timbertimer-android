package com.example.timbertimer.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.core.CALENDAR_MIN_MINUTES
import com.example.timbertimer.core.CalendarSegment
import com.example.timbertimer.core.Time
import com.example.timbertimer.core.buildSegments
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.ui.CalendarState
import com.example.timbertimer.ui.components.SegmentedRow
import com.example.timbertimer.ui.components.ClockFormat
import com.example.timbertimer.ui.components.currentLocale
import com.example.timbertimer.ui.components.rememberClockFormat
import com.example.timbertimer.ui.components.projectColors
import com.example.timbertimer.ui.components.projectLabel
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Dragging snaps to five minutes: fine enough to place a block exactly. */
private const val SNAP_MINUTES = 5
private val GUTTER_WIDTH = 46.dp
private val DAY_HEAD_HEIGHT = 46.dp

/** How close to a block's edge a press has to be to resize instead of move. */
private val EDGE_GRAB = 18.dp

/** Below this a block has no room for two grab zones, so it can only be moved. */
private val EDGE_GRAB_MIN_HEIGHT = 56.dp

private val DAY_OPTIONS = listOf(1, 3, 5, 7)

/**
 * The calendar: a Toggl-style day grid where every record is a block coloured by
 * its project, and where the day can actually be edited rather than only read.
 *
 * Long-press empty space and drag to block out a new record, long-press a block
 * to move it (across days too), or grab its top or bottom edge to change when it
 * started or ended. A tap opens the editor. Pinch zooms; a single finger always
 * scrolls, so the grid never fights the gesture that gets you around it.
 */
@Composable
fun CalendarScreen(
    state: CalendarState,
    records: List<FocusRecord>,
    timer: ActiveTimer?,
    now: Long,
    book: ProjectBook,
    onShift: (Int) -> Unit,
    onToday: () -> Unit,
    onDaysChange: (Int) -> Unit,
    onZoom: (Float) -> Unit,
    onOpenRecord: (FocusRecord) -> Unit,
    onCreateRecord: (Long, Int) -> Unit,
    onMoveRecord: (FocusRecord, Long, Int) -> Unit,
    onOpenTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val clock = rememberClockFormat()
    val minuteUnit = stringResource(R.string.unit_m)
    val hourUnit = stringResource(R.string.unit_h)
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val scroll = rememberScrollState()

    val dayStarts = remember(state.anchor, state.days) {
        List(state.days) { index -> Time.addDays(state.anchor, index.toLong()) }
    }

    // Blocks are recomputed a minute at a time rather than every second: only the
    // now-line and a running session move, and neither moves faster than that.
    val minuteStamp = now / 60_000L
    val segments = remember(records, timer?.id, timer?.startedAt, dayStarts, minuteStamp) {
        buildSegments(records, timer, dayStarts, minuteStamp * 60_000L)
    }

    var drag by remember { mutableStateOf<CalendarDrag?>(null) }

    // Nudge the day along when a drag reaches the top or bottom of the viewport,
    // so a block can be taken somewhere that is not on screen yet.
    var autoScroll by remember { mutableStateOf(0f) }
    LaunchedEffect(autoScroll) {
        if (autoScroll == 0f) return@LaunchedEffect
        while (true) {
            scroll.scrollTo((scroll.value + autoScroll).roundToInt().coerceAtLeast(0))
            kotlinx.coroutines.delay(16)
        }
    }

    // Open on the current hour the first time the screen is shown.
    LaunchedEffect(Unit) {
        val minutes = Time.minutesIntoDay(System.currentTimeMillis())
        val target = with(density) { ((minutes - 75) / 60f * state.zoomDp).dp.toPx() }
        scroll.scrollTo(target.roundToInt().coerceAtLeast(0))
    }

    // Zooming keeps whatever hour is at the top of the viewport pinned there.
    // Without this the day appears to run away from the finger, because the
    // scroll offset is in pixels and the content it indexes just changed height.
    var lastZoom by remember { mutableStateOf(state.zoomDp) }
    LaunchedEffect(state.zoomDp) {
        val previous = lastZoom
        lastZoom = state.zoomDp
        if (previous == state.zoomDp || previous <= 0f) return@LaunchedEffect
        scroll.scrollTo((scroll.value * (state.zoomDp / previous)).roundToInt().coerceAtLeast(0))
    }

    Column(modifier = modifier.fillMaxSize()) {
        CalendarToolbar(
            state = state,
            rangeLabel = Time.calendarRangeLabel(state.anchor, state.days, locale),
            onShift = onShift,
            onToday = onToday,
            onDaysChange = onDaysChange,
            onZoom = onZoom,
        )

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val gridWidth = maxWidth
            val dayWidth = (gridWidth - GUTTER_WIDTH) / state.days
            val hourHeight = state.zoomDp.dp
            val gutterPx = with(density) { GUTTER_WIDTH.toPx() }
            val dayWidthPx = with(density) { dayWidth.toPx() }
            val hourPx = with(density) { hourHeight.toPx() }

            val placed = remember(segments, dayWidthPx, hourPx, gutterPx) {
                place(segments, gutterPx, dayWidthPx, hourPx)
            }

            Column(Modifier.fillMaxSize()) {
                DayHeads(
                    dayStarts = dayStarts,
                    segments = segments,
                    dayWidth = dayWidth,
                    locale = locale,
                    formatMinutes = { Time.formatMinutes(it, minuteUnit, hourUnit) },
                )

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        // Two fingers zoom; one is always left to the scroller.
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    if (event.changes.size >= 2) {
                                        val zoom = event.calculateZoom()
                                        if (zoom != 1f && zoom > 0f) {
                                            onZoom(zoom)
                                            event.changes.forEach { it.consume() }
                                        }
                                    }
                                } while (event.changes.any { it.pressed })
                            }
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(hourHeight * 24)
                            .pointerInput(placed, hourPx, dayWidthPx, gutterPx, dayStarts) {
                                detectCalendarGestures(
                                    onTap = { offset ->
                                        val hit = placed.firstOrNull { it.contains(offset) }
                                        when {
                                            hit == null -> {
                                                val at = timeAt(offset, dayStarts, gutterPx, dayWidthPx, hourPx)
                                                if (at != null) onCreateRecord(at, 30)
                                            }

                                            hit.segment.running -> onOpenTimer()
                                            hit.segment.record != null -> onOpenRecord(hit.segment.record)
                                        }
                                    },
                                    onDragStart = { offset ->
                                        val started = startDrag(
                                            offset = offset,
                                            placed = placed,
                                            dayStarts = dayStarts,
                                            gutterPx = gutterPx,
                                            dayWidthPx = dayWidthPx,
                                            hourPx = hourPx,
                                            edgeGrabPx = with(density) { EDGE_GRAB.toPx() },
                                            edgeMinHeightPx = with(density) { EDGE_GRAB_MIN_HEIGHT.toPx() },
                                        )
                                        if (started != null) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                        drag = started
                                        started != null
                                    },
                                    onDrag = { offset ->
                                        val current = drag ?: return@detectCalendarGestures
                                        drag = current.movedTo(
                                            offset = offset,
                                            dayStarts = dayStarts,
                                            gutterPx = gutterPx,
                                            dayWidthPx = dayWidthPx,
                                            hourPx = hourPx,
                                        )
                                        // Only within the viewport's own bounds:
                                        // the offset here is grid-relative, so it
                                        // is compared against the visible window.
                                        val viewTop = scroll.value.toFloat()
                                        val viewBottom = viewTop + scroll.viewportSize
                                        autoScroll = when {
                                            offset.y < viewTop + hourPx * 0.5f -> -18f
                                            offset.y > viewBottom - hourPx * 0.5f -> 18f
                                            else -> 0f
                                        }
                                    },
                                    onDragEnd = {
                                        autoScroll = 0f
                                        val finished = drag
                                        drag = null
                                        if (finished == null) return@detectCalendarGestures
                                        val minutes = finished.minutes
                                        when {
                                            finished.record == null ->
                                                if (minutes >= CALENDAR_MIN_MINUTES) {
                                                    onCreateRecord(finished.start, minutes)
                                                }

                                            // A move keeps the record's own
                                            // length, whatever it was; only the
                                            // table's floor of one minute applies.
                                            else -> onMoveRecord(
                                                finished.record,
                                                finished.start,
                                                minutes.coerceAtLeast(1),
                                            )
                                        }
                                    },
                                    onDragCancel = {
                                        autoScroll = 0f
                                        drag = null
                                    },
                                )
                            },
                    ) {
                        GridLines(
                            days = state.days,
                            hourHeight = hourHeight,
                            dayWidth = dayWidth,
                        )
                        HourGutter(hourHeight = hourHeight, clock = clock)

                        placed.forEach { block ->
                            if (drag?.record != null && drag?.record?.id == block.segment.record?.id) return@forEach
                            CalendarBlock(
                                block = block,
                                book = book,
                                density = density,
                                clock = clock,
                            )
                        }

                        drag?.let { live ->
                            DragBlock(
                                drag = live,
                                book = book,
                                dayStarts = dayStarts,
                                gutter = GUTTER_WIDTH,
                                dayWidth = dayWidth,
                                hourHeight = hourHeight,
                                clock = clock,
                            )
                        }

                        NowLine(
                            dayStarts = dayStarts,
                            now = now,
                            gutter = GUTTER_WIDTH,
                            dayWidth = dayWidth,
                            hourHeight = hourHeight,
                        )
                    }
                }
            }
        }
    }
}

// ---------- toolbar ----------

@Composable
private fun CalendarToolbar(
    state: CalendarState,
    rangeLabel: String,
    onShift: (Int) -> Unit,
    onToday: () -> Unit,
    onDaysChange: (Int) -> Unit,
    onZoom: (Float) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onShift(-1) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(R.string.calendar_prev),
                )
            }
            Text(
                text = rangeLabel,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onShift(1) }) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.calendar_next),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedRow(
                options = DAY_OPTIONS,
                selected = state.days,
                label = { "$it" },
                onSelect = onDaysChange,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onToday) {
                Text(stringResource(R.string.calendar_today), maxLines = 1)
            }
            IconButton(onClick = { onZoom(1f / 1.3f) }) {
                Icon(
                    Icons.Filled.Remove,
                    contentDescription = stringResource(R.string.calendar_zoom_out),
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = { onZoom(1.3f) }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = stringResource(R.string.calendar_zoom_in),
                    modifier = Modifier.size(18.dp),
                )
            }
        }

        Text(
            text = stringResource(R.string.calendar_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun DayHeads(
    dayStarts: List<Long>,
    segments: List<CalendarSegment>,
    dayWidth: Dp,
    locale: java.util.Locale,
    formatMinutes: (Int) -> String,
) {
    val todayKey = Time.localDateKey(System.currentTimeMillis())
    Row(modifier = Modifier.height(DAY_HEAD_HEIGHT)) {
        Spacer(Modifier.width(GUTTER_WIDTH))
        dayStarts.forEachIndexed { index, dayStart ->
            val isToday = Time.localDateKey(dayStart) == todayKey
            // A running session has not been recorded yet, so it is left out of
            // the day's total rather than inflating it minute by minute.
            val total = segments
                .filter { it.dayIndex == index && it.record != null }
                .sumOf { it.minutes }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                    .width(dayWidth)
                    .fillMaxHeight()
                    .then(
                        if (isToday) {
                            Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                        } else Modifier
                    ),
            ) {
                Text(
                    text = Time.weekdayShort(dayStart, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isToday) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = Time.dayAndMonth(dayStart, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                Text(
                    text = if (total > 0) formatMinutes(total) else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun GridLines(days: Int, hourHeight: Dp, dayWidth: Dp) {
    val line = MaterialTheme.colorScheme.outline
    val faint = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = Modifier.fillMaxSize()) {
        val hourPx = hourHeight.toPx()
        val gutterPx = GUTTER_WIDTH.toPx()
        val dayPx = dayWidth.toPx()

        for (hour in 0..24) {
            val y = hour * hourPx
            drawLine(
                color = if (hour % 6 == 0) line else faint,
                start = Offset(gutterPx, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
        }
        for (day in 0..days) {
            val x = gutterPx + day * dayPx
            drawLine(
                color = faint,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
        }
    }
}

@Composable
private fun HourGutter(hourHeight: Dp, clock: ClockFormat) {
    // From 1: a label at midnight would be clipped by the header above it.
    for (hour in 1..23) {
        Text(
            text = clock.hour(hour),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier
                .width(GUTTER_WIDTH)
                .offset(y = hourHeight * hour - 7.dp)
                .padding(end = 6.dp),
        )
    }
}

@Composable
private fun NowLine(
    dayStarts: List<Long>,
    now: Long,
    gutter: Dp,
    dayWidth: Dp,
    hourHeight: Dp,
) {
    val todayKey = Time.localDateKey(now)
    val index = dayStarts.indexOfFirst { Time.localDateKey(it) == todayKey }
    if (index < 0) return
    val minutes = Time.minutesIntoDay(now)
    val color = MaterialTheme.colorScheme.error

    Box(
        modifier = Modifier
            .offset(x = gutter + dayWidth * index, y = hourHeight * (minutes / 60f) - 1.dp)
            .width(dayWidth)
            .height(2.dp)
            .background(color)
    )
    Box(
        modifier = Modifier
            .offset(x = gutter + dayWidth * index - 3.dp, y = hourHeight * (minutes / 60f) - 4.dp)
            .size(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(color)
    )
}

@Composable
private fun CalendarBlock(
    block: PlacedSegment,
    book: ProjectBook,
    density: androidx.compose.ui.unit.Density,
    clock: ClockFormat,
) {
    val segment = block.segment
    val project = book[segment.projectId]
    val colors = projectColors(project)
    val heightDp = with(density) { block.heightPx.toDp() }
    val compact = heightDp < 34.dp

    Box(
        modifier = Modifier
            .offset(
                x = with(density) { block.leftPx.toDp() },
                y = with(density) { block.topPx.toDp() },
            )
            .width(with(density) { block.widthPx.toDp() })
            .height(heightDp)
            .padding(end = 2.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            // An abandoned session still occupied the time, so it keeps its
            // place on the grid — drawn fainter, the way its tree is drawn wilted.
            .alpha(if (segment.status == RecordStatus.ABANDONED) 0.62f else 1f)
            .background(colors.soft)
            .padding(start = 5.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
    ) {
        // The project's own colour as a spine down the left edge, so a block is
        // identifiable even when it is too short for any text.
        Box(
            modifier = Modifier
                .offset(x = (-4).dp)
                .fillMaxHeight()
                .width(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (segment.running) colors.base.copy(alpha = 0.6f) else colors.base)
        )

        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                text = segment.title,
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!compact) {
                Text(
                    text = projectLabel(project),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (heightDp >= 52.dp) {
                Text(
                    text = if (segment.running) {
                        "${clock.time(segment.startMillis)} · " +
                            stringResource(R.string.calendar_running)
                    } else {
                        "${clock.time(segment.startMillis)} – ${clock.time(segment.endMillis)}"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The block being dragged, drawn over the grid at wherever it currently is. */
@Composable
private fun DragBlock(
    drag: CalendarDrag,
    book: ProjectBook,
    dayStarts: List<Long>,
    gutter: Dp,
    dayWidth: Dp,
    hourHeight: Dp,
    clock: ClockFormat,
) {
    if (drag.dayIndex !in dayStarts.indices) return
    val dayStart = dayStarts[drag.dayIndex]
    val startMin = ((drag.start - dayStart) / 60_000f).coerceIn(0f, 1440f)
    val endMin = ((drag.end - dayStart) / 60_000f).coerceIn(startMin, 1440f)
    val project = book[drag.record?.projectId ?: drag.projectId]
    val colors = projectColors(project)

    Box(
        modifier = Modifier
            .offset(x = gutter + dayWidth * drag.dayIndex, y = hourHeight * (startMin / 60f))
            .width(dayWidth)
            .height((hourHeight * ((endMin - startMin) / 60f)).coerceAtLeast(18.dp))
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.soft)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Column {
            Text(
                text = "${clock.time(drag.start)} – ${clock.time(drag.end)}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.ink,
                maxLines = 1,
            )
            Text(
                text = "${drag.minutes}m",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

// ---------- geometry ----------

data class PlacedSegment(
    val segment: CalendarSegment,
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
) {
    fun contains(offset: Offset): Boolean =
        offset.x >= leftPx && offset.x < leftPx + widthPx &&
            offset.y >= topPx && offset.y < topPx + heightPx
}

private fun place(
    segments: List<CalendarSegment>,
    gutterPx: Float,
    dayWidthPx: Float,
    hourPx: Float,
): List<PlacedSegment> = segments.map { segment ->
    val columnWidth = dayWidthPx / segment.columns
    PlacedSegment(
        segment = segment,
        leftPx = gutterPx + segment.dayIndex * dayWidthPx + segment.column * columnWidth,
        topPx = segment.startMin / 60f * hourPx,
        widthPx = columnWidth,
        heightPx = ((segment.endMin - segment.startMin) / 60f * hourPx).coerceAtLeast(6f),
    )
}

/** The instant a point on the grid stands for, snapped to five minutes. */
private fun timeAt(
    offset: Offset,
    dayStarts: List<Long>,
    gutterPx: Float,
    dayWidthPx: Float,
    hourPx: Float,
): Long? {
    val index = dayIndexAt(offset.x, dayStarts.size, gutterPx, dayWidthPx) ?: return null
    val minutes = (offset.y / hourPx * 60f)
    val snapped = (minutes / SNAP_MINUTES).roundToLong() * SNAP_MINUTES
    return dayStarts[index] + snapped.coerceIn(0L, 1440L) * 60_000L
}

private fun dayIndexAt(x: Float, days: Int, gutterPx: Float, dayWidthPx: Float): Int? {
    if (days <= 0 || dayWidthPx <= 0f) return null
    return floor((x - gutterPx) / dayWidthPx).toInt().coerceIn(0, days - 1)
}

// ---------- dragging ----------

enum class DragMode { CREATE, MOVE, RESIZE_START, RESIZE_END }

/**
 * A live drag. [start] and [end] are the instants the block currently covers;
 * they are recomputed from the pointer on every move rather than accumulated, so
 * a drag cannot slowly drift away from the finger.
 */
data class CalendarDrag(
    val mode: DragMode,
    val record: FocusRecord?,
    val projectId: String,
    val dayIndex: Int,
    val start: Long,
    val end: Long,
    /** Where in the block it was grabbed, so a move keeps that grip. */
    val grabOffsetMillis: Long,
    /**
     * Where a new block was started from. Kept apart from [start] and [end]
     * because those are already sorted: without it, dragging back past the
     * beginning would make the block grow from wherever it currently starts
     * rather than from where the finger first went down.
     */
    val anchor: Long,
) {
    val minutes: Int get() = (((end - start) / 60_000f).roundToInt()).coerceAtLeast(0)
}

private fun startDrag(
    offset: Offset,
    placed: List<PlacedSegment>,
    dayStarts: List<Long>,
    gutterPx: Float,
    dayWidthPx: Float,
    hourPx: Float,
    edgeGrabPx: Float,
    edgeMinHeightPx: Float,
): CalendarDrag? {
    val hit = placed.firstOrNull { it.contains(offset) }
    val at = timeAt(offset, dayStarts, gutterPx, dayWidthPx, hourPx) ?: return null

    if (hit == null) {
        val index = dayIndexAt(offset.x, dayStarts.size, gutterPx, dayWidthPx) ?: return null
        return CalendarDrag(DragMode.CREATE, null, "", index, at, at, 0L, at)
    }

    val segment = hit.segment
    // Neither the timer that is still running nor one piece of a record that
    // crosses midnight is a whole block, so neither can be dragged.
    if (segment.running || segment.partial || segment.record == null) return null

    val record = segment.record
    val start = record.startedAt
    val end = maxOf(record.endsAt, record.startedAt)
    val fromTop = offset.y - hit.topPx
    val canResize = hit.heightPx >= edgeMinHeightPx

    val mode = when {
        canResize && fromTop <= edgeGrabPx -> DragMode.RESIZE_START
        canResize && fromTop >= hit.heightPx - edgeGrabPx -> DragMode.RESIZE_END
        else -> DragMode.MOVE
    }

    return CalendarDrag(
        mode = mode,
        record = record,
        projectId = record.projectId,
        dayIndex = segment.dayIndex,
        start = start,
        end = end,
        grabOffsetMillis = at - start,
        anchor = start,
    )
}

private fun CalendarDrag.movedTo(
    offset: Offset,
    dayStarts: List<Long>,
    gutterPx: Float,
    dayWidthPx: Float,
    hourPx: Float,
): CalendarDrag {
    // Only a move may change day; creating and resizing stay where they began.
    val index = if (mode == DragMode.MOVE) {
        dayIndexAt(offset.x, dayStarts.size, gutterPx, dayWidthPx) ?: dayIndex
    } else dayIndex
    if (index !in dayStarts.indices) return this

    val dayStart = dayStarts[index]
    val dayEnd = dayStart + 1440L * 60_000L
    val minutes = (offset.y / hourPx * 60f)
    val snapped = ((minutes / SNAP_MINUTES).roundToLong() * SNAP_MINUTES).coerceIn(0L, 1440L)
    val at = dayStart + snapped * 60_000L
    val minSpan = CALENDAR_MIN_MINUTES * 60_000L

    return when (mode) {
        DragMode.CREATE -> copy(
            dayIndex = index,
            start = minOf(anchor, at),
            end = maxOf(anchor, at),
        )

        DragMode.MOVE -> {
            val span = end - start
            // A record stays inside a single day, so a move that would spill
            // over midnight stops at the edge instead.
            val newStart = (at - grabOffsetMillis).coerceIn(dayStart, maxOf(dayStart, dayEnd - span))
            copy(dayIndex = index, start = newStart, end = newStart + span)
        }

        // Both bounds are clamped into the day before they are used as a range:
        // a record that ends four minutes after midnight has no room for the
        // minimum span, and an inverted range is an exception rather than a
        // no-op.
        DragMode.RESIZE_START ->
            copy(start = at.coerceIn(dayStart, maxOf(dayStart, end - minSpan)))

        DragMode.RESIZE_END ->
            copy(end = at.coerceIn(minOf(dayEnd, start + minSpan), dayEnd))
    }
}

/**
 * Tap, or long-press-then-drag — and nothing in between.
 *
 * Written by hand rather than composed from the stock detectors because both
 * have to share one gesture *and* leave a plain drag alone: a finger that moves
 * before the press completes is scrolling the day, and the scroller above must
 * be free to take it. Nothing is consumed until a long press has actually
 * happened, which is what makes that possible.
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectCalendarGestures(
    onTap: (Offset) -> Unit,
    onDragStart: (Offset) -> Boolean,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val startPosition = down.position
        val slop = viewConfiguration.touchSlop

        // Null means the long press timed out with the finger still down and
        // still; anything else ended the gesture before that.
        var wasTap = false
        val settled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    wasTap = true
                    break
                }
                if ((change.position - startPosition).getDistance() > slop) break
            }
            Unit
        }

        when {
            settled != null && wasTap -> onTap(startPosition)
            // A scroll, or a stray pointer — leave it to the scroller above.
            settled != null -> Unit
            else -> {
                if (!onDragStart(startPosition)) return@awaitEachGesture
                var cancelled = false
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        cancelled = true
                        break
                    }
                    if (!change.pressed) break
                    // Consumed only now, so the scroller cannot also act on it.
                    change.consume()
                    onDrag(change.position)
                }
                if (cancelled) onDragCancel() else onDragEnd()
            }
        }
    }
}
