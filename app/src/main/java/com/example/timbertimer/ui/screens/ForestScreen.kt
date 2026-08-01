package com.example.timbertimer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
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
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.ui.GroveView
import com.example.timbertimer.ui.components.Panel
import com.example.timbertimer.ui.components.SegmentedRow
import com.example.timbertimer.ui.components.TreeArt
import com.example.timbertimer.ui.components.treePalette
import com.example.timbertimer.ui.components.currentLocale

/**
 * The forest: every tree planted in the chosen window, drawn at the size its
 * session earned.
 *
 * A grid rather than the website's flowing row, because a phone is tall and a
 * tablet is wide, and an adaptive column count keeps the trees the same size on
 * both instead of stretching them.
 */
@Composable
fun ForestScreen(
    records: List<FocusRecord>,
    view: GroveView,
    anchor: Long,
    onViewChange: (GroveView) -> Unit,
    onShift: (Int) -> Unit,
    onCurrent: () -> Unit,
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
    val planted = records
        .filter { it.status == RecordStatus.COMPLETED }
        .filter { (it.endedAt.takeIf { end -> end > 0 } ?: it.startedAt) in rangeStart until rangeEnd }
        .sortedBy { it.startedAt }

    val focusMinutes = planted.filterNot { it.isRest }.sumOf { it.actualMinutes }
    val restMinutes = planted.filter { it.isRest }.sumOf { it.actualMinutes }

    val rangeLabel = when (view) {
        GroveView.TODAY -> Time.todayLabel(rangeStart, locale)
        GroveView.WEEK -> Time.weekRangeLabel(rangeStart, locale)
        GroveView.MONTH -> Time.monthLabel(rangeStart, locale)
    }

    Column(modifier = modifier.fillMaxWidth()) {
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

            if (view != GroveView.TODAY) {
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

        Spacer(Modifier.height(16.dp))

        if (planted.isEmpty()) {
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
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 92.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                // Bounded so it can live inside the screen's own scroll without
                // two scrollers fighting over the same gesture.
                modifier = Modifier.heightIn(max = 2400.dp),
            ) {
                items(planted, key = { it.id }) { record -> GroveTree(record) }
            }
        }
    }
}

@Composable
private fun GroveTree(record: FocusRecord) {
    val seed = Seed.treeSeed(record.title)
    // Every tree is nudged off the grid a little — a different lean, a different
    // height — so a week's planting reads as a wood rather than a chart.
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
                species = record.species,
                palette = treePalette(seed),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .scale(scale / 0.74f)
                    .rotate(tilt)
                    .offset(x = shift.dp),
            )
        }
        Text(
            text = if (record.isRest) stringResource(R.string.rest_record_title) else record.title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(88.dp),
        )
    }
}
