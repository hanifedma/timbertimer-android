package com.example.timbertimer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.core.Seed
import com.example.timbertimer.core.Time
import com.example.timbertimer.core.groveTreeScale
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.ui.GroveView
import com.example.timbertimer.ui.components.Panel
import com.example.timbertimer.ui.components.ProjectSummary
import com.example.timbertimer.ui.components.SegmentedRow
import com.example.timbertimer.ui.components.TreeArt
import com.example.timbertimer.ui.components.currentLocale
import com.example.timbertimer.ui.components.projectLabel
import com.example.timbertimer.ui.components.projectTotals
import com.example.timbertimer.ui.components.rememberTreePalette

/**
 * The forest: every tree planted in the chosen window, drawn at the size its
 * session earned, plus where that window's hours actually went.
 *
 * A grid rather than the website's flowing row, because a phone is tall and a
 * tablet is wide, and an adaptive column count keeps the trees the same size on
 * both instead of stretching them.
 */
@Composable
fun ForestScreen(
    records: List<FocusRecord>,
    book: ProjectBook,
    view: GroveView,
    anchor: Long,
    onViewChange: (GroveView) -> Unit,
    onShift: (Int) -> Unit,
    onCurrent: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val minuteUnit = stringResource(R.string.unit_m)
    val hourUnit = stringResource(R.string.unit_h)

    val rangeStart: Long
    val rangeEnd: Long
    when (view) {
        GroveView.TODAY -> {
            rangeStart = Time.startOfDay(anchor)
            rangeEnd = Time.addDays(rangeStart, 1)
        }

        GroveView.WEEK -> {
            rangeStart = Time.startOfWeek(anchor)
            rangeEnd = Time.addDays(rangeStart, 7)
        }

        GroveView.MONTH -> {
            rangeStart = Time.startOfMonth(anchor)
            rangeEnd = Time.startOfMonth(Time.addMonths(rangeStart, 1))
        }
    }

    // A tree belongs to the day it was planted on, which is when it ended.
    val inRange = remember(records, rangeStart, rangeEnd) {
        records
            .filter { (it.endedAt.takeIf { end -> end > 0 } ?: it.startedAt) in rangeStart until rangeEnd }
            .sortedBy { it.startedAt }
    }
    // Trees come from completed sessions — an abandoned one grows nothing — but
    // the time it took still counts toward the totals.
    val planted = inRange.filter { it.status == RecordStatus.COMPLETED }
    val focusMinutes = inRange.filterNot { it.isRest }.sumOf { it.actualMinutes }
    val restMinutes = inRange.filter { it.isRest }.sumOf { it.actualMinutes }
    val totals = remember(inRange, book) { projectTotals(inRange, book) }

    val rangeLabel = when (view) {
        GroveView.TODAY -> Time.todayLabel(rangeStart, locale)
        GroveView.WEEK -> Time.weekRangeLabel(rangeStart, locale)
        GroveView.MONTH -> Time.monthLabel(rangeStart, locale)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 92.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = contentPadding,
        modifier = modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Panel(
                kicker = stringResource(
                    when (view) {
                        GroveView.TODAY -> R.string.grove_today
                        GroveView.WEEK -> R.string.grove_weekly
                        GroveView.MONTH -> R.string.grove_monthly
                    }
                ),
                title = rangeLabel,
            ) {
                SegmentedRow(
                    options = listOf(GroveView.TODAY, GroveView.WEEK, GroveView.MONTH),
                    selected = view,
                    label = {
                        stringResource(
                            when (it) {
                                GroveView.TODAY -> R.string.grove_view_today
                                GroveView.WEEK -> R.string.grove_view_week
                                GroveView.MONTH -> R.string.grove_view_month
                            }
                        )
                    },
                    onSelect = onViewChange,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { onShift(-1) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = stringResource(R.string.grove_previous),
                        )
                    }
                    TextButton(onClick = onCurrent, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.grove_current), maxLines = 1)
                    }
                    IconButton(
                        onClick = { onShift(1) },
                        // Nothing has been planted in the future.
                        enabled = rangeEnd <= System.currentTimeMillis(),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = stringResource(R.string.grove_next),
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = pluralStringResource(R.plurals.grove_trees, planted.size, planted.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.grove_focused,
                            Time.formatMinutes(focusMinutes, minuteUnit, hourUnit),
                        ),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (restMinutes > 0) {
                        Text(
                            text = stringResource(
                                R.string.grove_rested,
                                Time.formatMinutes(restMinutes, minuteUnit, hourUnit),
                            ),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(12.dp))
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Panel(
                kicker = stringResource(R.string.summary_kicker),
                title = stringResource(R.string.summary_title),
            ) {
                ProjectSummary(
                    rows = totals,
                    formatMinutes = { Time.formatMinutes(it, minuteUnit, hourUnit) },
                )
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) {
            Spacer(Modifier.height(12.dp))
        }

        if (planted.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Panel {
                    Text(
                        text = stringResource(
                            when (view) {
                                GroveView.TODAY -> R.string.grove_empty_today
                                GroveView.WEEK -> R.string.grove_empty_week
                                GroveView.MONTH -> R.string.grove_empty_month
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            items(planted, key = { it.id }) { record -> GroveTree(record, book) }
        }
    }
}

@Composable
private fun GroveTree(record: FocusRecord, book: ProjectBook) {
    val project = book.projectFor(record)
    // The jitter that keeps the forest from looking like a plantation stays
    // per-record, so two trees of the same project still differ.
    val seed = remember(project.id, record.id) { "${project.id}:${record.id}" }
    val scale = groveTreeScale(record.actualMinutes, seed).coerceIn(0.35f, 0.9f)
    val tilt = Seed.range(seed, "tilt", -5f, 5f)
    val shift = Seed.range(seed, "shift", -4f, 4f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .padding(bottom = 2.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            TreeArt(
                species = book.speciesFor(record),
                palette = rememberTreePalette(project),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .scale(scale / 0.74f)
                    .rotate(tilt)
                    .offset(x = shift.dp),
            )
        }
        Text(
            text = projectLabel(project),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(88.dp),
        )
    }
}
