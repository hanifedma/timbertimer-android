package com.example.timbertimer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.core.Seed
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.ui.components.Panel
import com.example.timbertimer.ui.components.SegmentedRow
import com.example.timbertimer.ui.components.TreeArt
import com.example.timbertimer.ui.components.treePalette
import com.example.timbertimer.ui.components.currentLocale
import java.util.Locale

/** Filter options, with null standing for "all". */
private val FILTERS = listOf<RecordStatus?>(null, RecordStatus.COMPLETED, RecordStatus.ABANDONED)

/**
 * Focus history: today's and all-time totals, a searchable list, and the ability
 * to correct or add a session by hand — the same affordances the website has.
 */
@Composable
fun RecordsScreen(
    records: List<FocusRecord>,
    allRecords: List<FocusRecord>,
    query: String,
    status: RecordStatus?,
    onQueryChange: (String) -> Unit,
    onStatusChange: (RecordStatus?) -> Unit,
    onAdd: () -> Unit,
    onEdit: (FocusRecord) -> Unit,
    onDelete: (FocusRecord) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = currentLocale()
    val minuteUnit = stringResource(R.string.unit_m)
    val hourUnit = stringResource(R.string.unit_h)

    // Rests are planted trees, but they are not focus — they never count here.
    val focus = allRecords.filter { it.status == RecordStatus.COMPLETED && !it.isRest }
    val todayKey = Time.localDateKey(System.currentTimeMillis())
    val todayMinutes = focus
        .filter { Time.localDateKey(if (it.endedAt > 0) it.endedAt else it.startedAt) == todayKey }
        .sumOf { it.actualMinutes }
    val totalMinutes = focus.sumOf { it.actualMinutes }

    Column(modifier = modifier.fillMaxWidth()) {
        Panel(
            kicker = stringResource(R.string.records_kicker),
            title = stringResource(R.string.records_title),
            trailing = {
                IconButton(onClick = onAdd) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.records_add))
                }
            },
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile(
                    label = stringResource(R.string.stats_today),
                    value = Time.formatMinutes(todayMinutes, minuteUnit, hourUnit),
                    modifier = Modifier.weight(1f),
                )
                StatTile(
                    label = stringResource(R.string.stats_total),
                    value = Time.formatMinutes(totalMinutes, minuteUnit, hourUnit),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.records_search)) },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(10.dp))

            SegmentedRow(
                options = FILTERS,
                selected = status,
                label = {
                    stringResource(
                        when (it) {
                            null -> R.string.filter_all
                            RecordStatus.COMPLETED -> R.string.filter_completed
                            RecordStatus.ABANDONED -> R.string.filter_abandoned
                        }
                    )
                },
                onSelect = onStatusChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))

        if (records.isEmpty()) {
            Panel {
                Text(
                    text = stringResource(R.string.records_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                records.forEach { record ->
                    RecordCard(
                        record = record,
                        locale = locale,
                        onEdit = { onEdit(record) },
                        onDelete = { onDelete(record) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RecordCard(
    record: FocusRecord,
    locale: Locale,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val isRest = record.isRest
    Panel(contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TreeArt(
                species = record.species,
                palette = treePalette(record.title),
                modifier = Modifier.size(44.dp),
            )
            Spacer(Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (isRest) stringResource(R.string.rest_record_title) else record.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    StatusBadge(record, isRest)
                }
                Text(
                    text = Time.recordDateLabel(record.startedAt, locale),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.metric_focused, record.actualMinutes) +
                        " · " + stringResource(R.string.metric_goal, record.durationMinutes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            IconButton(onClick = onEdit) {
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.action_edit),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusBadge(record: FocusRecord, isRest: Boolean) {
    val (text, color) = when {
        isRest -> stringResource(R.string.record_rested) to MaterialTheme.colorScheme.tertiary
        record.status == RecordStatus.COMPLETED ->
            stringResource(R.string.record_planted) to MaterialTheme.colorScheme.primary

        else -> stringResource(R.string.record_abandoned) to MaterialTheme.colorScheme.error
    }
    Surface(shape = RoundedCornerShape(6.dp), color = color.copy(alpha = 0.16f)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
