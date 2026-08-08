package com.example.timbertimer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.data.model.ProjectBook
import kotlin.math.roundToInt

/** One project's share of a period. */
data class ProjectTotal(
    val project: Project,
    val minutes: Int,
    val count: Int,
)

/**
 * Time tracked, not trees earned: an abandoned session still cost the time, it
 * just never grew anything, so it counts here.
 */
fun projectTotals(records: List<FocusRecord>, book: ProjectBook): List<ProjectTotal> {
    val totals = LinkedHashMap<String, ProjectTotal>()
    records.forEach { record ->
        val minutes = record.actualMinutes
        if (minutes <= 0) return@forEach
        val existing = totals[record.projectId]
        totals[record.projectId] = if (existing == null) {
            ProjectTotal(book[record.projectId], minutes, 1)
        } else {
            existing.copy(minutes = existing.minutes + minutes, count = existing.count + 1)
        }
    }
    return totals.values.sortedByDescending { it.minutes }
}

/**
 * Where a period's hours went: one arc per project, in that project's colour,
 * with the same figures spelled out beneath.
 *
 * The chart is only half the answer — an arc cannot be read precisely and cannot
 * be read at all without colour vision — so the legend carries the real numbers
 * and the ring is what makes the balance obvious at a glance.
 */
@Composable
fun ProjectSummary(
    rows: List<ProjectTotal>,
    formatMinutes: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val totalMinutes = rows.sumOf { it.minutes }

    if (rows.isEmpty() || totalMinutes <= 0) {
        Text(
            text = stringResource(R.string.summary_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
        )
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProjectDonut(
            rows = rows,
            totalMinutes = totalMinutes,
            centreLabel = formatMinutes(totalMinutes),
            modifier = Modifier.size(120.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rows.forEach { row ->
                LegendRow(row, totalMinutes, formatMinutes)
            }
        }
    }
}

@Composable
private fun ProjectDonut(
    rows: List<ProjectTotal>,
    totalMinutes: Int,
    centreLabel: String,
    modifier: Modifier = Modifier,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val slices = rows.map { solidColor(it.project.color) to it.minutes.toFloat() / totalMinutes }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(120.dp)) {
            val stroke = size.minDimension * 0.16f
            val diameter = size.minDimension - stroke
            val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
            val arcSize = Size(diameter, diameter)

            drawArc(
                color = track,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke),
            )

            var start = -90f
            slices.forEach { (color, share) ->
                val sweep = share * 360f
                // A hairline gap separates neighbours, but never on a lone ring
                // — a full circle drawn short of 360° reads as a bug.
                val gap = if (slices.size > 1) minOf(2.5f, sweep / 4f) else 0f
                drawArc(
                    color = color,
                    startAngle = start,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.6f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        Text(
            text = centreLabel,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}

@Composable
private fun LegendRow(row: ProjectTotal, totalMinutes: Int, formatMinutes: (Int) -> String) {
    val percent = (row.minutes.toFloat() / totalMinutes * 100f).roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        ProjectDot(row.project, size = 10.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = projectLabel(row.project),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = formatMinutes(row.minutes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "$percent%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(36.dp),
            textAlign = TextAlign.End,
        )
    }
}
