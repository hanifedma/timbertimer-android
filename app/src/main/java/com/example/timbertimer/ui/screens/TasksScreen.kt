package com.example.timbertimer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.timbertimer.R
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.Note
import com.example.timbertimer.ui.components.Panel
import kotlin.math.abs

/**
 * The shared to-do list.
 *
 * Reordering is a drag on the grip handle, as on the website. Rows can wrap to
 * two lines, so the drop target is worked out from each row's measured height
 * rather than from a nominal one — with a fixed guess, one long task is enough
 * to make every drop after it land a row off.
 */
@Composable
fun TasksScreen(
    notes: List<Note>,
    onAdd: (String) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }

    // Done items sink to the bottom, as on the website. This is also the order a
    // drag rearranges, and the order committed when it ends.
    val ordered = remember(notes) { notes.filterNot { it.done } + notes.filter { it.done } }
    // Gesture callbacks outlive the composition that created them, so anything
    // they read has to be a live handle rather than a captured value.
    val liveOrdered by rememberUpdatedState(ordered)

    // Live during a drag: which row is held, and how far it has travelled.
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { mutableStateListOf<Float>() }
    val haptics = LocalHapticFeedback.current

    // Deleting a task would otherwise leave a measurement behind, and the drop
    // target would be computed against a row that is no longer on screen.
    LaunchedEffect(ordered.size) {
        while (rowHeights.size > ordered.size) rowHeights.removeAt(rowHeights.lastIndex)
    }

    // The row the held one would displace, given how far it has been dragged.
    val targetIndex = draggedIndex?.let { from ->
        dropTarget(from, dragDistance, rowHeights, ordered.size)
    }

    Panel(
        modifier = modifier,
        kicker = stringResource(R.string.notes_kicker),
        title = stringResource(R.string.notes_title),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.take(Limits.NOTE_MAX) },
                placeholder = { Text(stringResource(R.string.notes_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (draft.isNotBlank()) {
                        onAdd(draft)
                        draft = ""
                    }
                }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (draft.isNotBlank()) {
                        onAdd(draft)
                        draft = ""
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.notes_add))
            }
        }

        Spacer(Modifier.height(8.dp))

        if (ordered.isEmpty()) {
            Text(
                text = stringResource(R.string.notes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 24.dp),
            )
            return@Panel
        }

        Column {
            ordered.forEachIndexed { index, note ->
                val isDragged = draggedIndex == index
                // Rows between the held one and its target slide out of the way,
                // so the gap always shows where it would land.
                val shift = displacement(index, draggedIndex, targetIndex, rowHeights)

                Column(
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (isDragged) dragDistance else shift
                            // Lifted off the page while held.
                            scaleX = if (isDragged) 1.02f else 1f
                            scaleY = if (isDragged) 1.02f else 1f
                            alpha = if (isDragged) 0.94f else 1f
                        }
                        .onGloballyPositioned { coordinates ->
                            val measured = coordinates.size.height.toFloat()
                            while (rowHeights.size <= index) rowHeights.add(measured)
                            if (rowHeights[index] != measured) rowHeights[index] = measured
                        }
                        .then(
                            if (isDragged) {
                                Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            } else Modifier
                        ),
                ) {
                    NoteRow(
                        note = note,
                        position = index,
                        count = ordered.size,
                        onToggle = { onToggle(note.id) },
                        onDelete = { onDelete(note.id) },
                        onMoveBy = { delta ->
                            onReorder(moved(ordered.map { it.id }, index, index + delta))
                        },
                        dragModifier = Modifier.pointerInput(ordered.size, index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggedIndex = index
                                    dragDistance = 0f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragDistance += amount.y
                                },
                                onDragEnd = {
                                    // Recomputed here rather than read off the
                                    // composition: this lambda was built when the
                                    // gesture started, so a value captured then
                                    // would still say "not dragging".
                                    val from = draggedIndex
                                    val ids = liveOrdered.map { it.id }
                                    val to = from?.let {
                                        dropTarget(it, dragDistance, rowHeights, ids.size)
                                    }
                                    draggedIndex = null
                                    dragDistance = 0f
                                    if (from != null && to != null && from != to) {
                                        onReorder(moved(ids, from, to))
                                    }
                                },
                                onDragCancel = {
                                    draggedIndex = null
                                    dragDistance = 0f
                                },
                            )
                        },
                    )
                    if (index != ordered.lastIndex) {
                        // Always drawn. Hiding it while dragging would shorten the
                        // row mid-gesture, which both jumps the layout and
                        // corrupts the height the drop target is measured against.
                        HorizontalDivider(
                            color = if (isDragged) MaterialTheme.colorScheme.surfaceContainerHigh
                            else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

/**
 * How far a held row has to travel before it swaps with its neighbour: past the
 * midpoint of that neighbour, measured with the neighbour's own height.
 */
private fun dropTarget(from: Int, distance: Float, heights: List<Float>, count: Int): Int {
    if (heights.isEmpty() || count <= 1) return from
    var target = from
    var remaining = abs(distance)
    val step = if (distance < 0) -1 else 1

    while (true) {
        val next = target + step
        if (next < 0 || next >= count || next >= heights.size) break
        val threshold = heights[next] / 2f
        if (remaining < threshold) break
        remaining -= heights[next]
        target = next
    }
    return target
}

/** The offset a row slides by while another is being dragged over it. */
private fun displacement(index: Int, from: Int?, to: Int?, heights: List<Float>): Float {
    if (from == null || to == null || from == to || index == from) return 0f
    val held = heights.getOrNull(from) ?: return 0f
    return when {
        from < to && index in (from + 1)..to -> -held
        to < from && index in to until from -> held
        else -> 0f
    }
}

/** The list with the item at [from] moved to [to]. */
private fun moved(ids: List<String>, from: Int, to: Int): List<String> {
    if (from !in ids.indices || to !in ids.indices || from == to) return ids
    return ids.toMutableList().apply { add(to, removeAt(from)) }
}

@Composable
private fun NoteRow(
    note: Note,
    position: Int,
    count: Int,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onMoveBy: (Int) -> Unit,
    dragModifier: Modifier,
) {
    val moveUp = stringResource(R.string.notes_move_up)
    val moveDown = stringResource(R.string.notes_move_down)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            // A drag is invisible to a screen reader, so the same two moves stay
            // reachable as accessibility actions on the row.
            .semantics {
                customActions = buildList {
                    if (position > 0) add(CustomAccessibilityAction(moveUp) { onMoveBy(-1); true })
                    if (position < count - 1) {
                        add(CustomAccessibilityAction(moveDown) { onMoveBy(1); true })
                    }
                }
            },
    ) {
        Icon(
            Icons.Filled.DragIndicator,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = dragModifier
                .padding(end = 2.dp)
                .size(24.dp),
        )
        Checkbox(
            checked = note.done,
            onCheckedChange = { onToggle() },
        )
        Text(
            text = note.text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (note.done) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (note.done) TextDecoration.LineThrough else null,
            modifier = Modifier
                .weight(1f)
                .padding(end = 4.dp),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.notes_delete),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
