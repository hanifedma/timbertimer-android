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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.Note
import com.example.timbertimer.ui.components.Panel

/**
 * The shared to-do list.
 *
 * Reordering is done with explicit up/down buttons rather than the website's
 * drag handle. Dragging inside a scrolling list on a touch screen is a fight
 * between two gestures, and the buttons are also the only version a screen
 * reader can drive.
 */
@Composable
fun TasksScreen(
    notes: List<Note>,
    onAdd: (String) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }

    // Done items sink to the bottom, as on the website. The stored order is
    // untouched, so moving an item still means what the user meant.
    val ordered = remember(notes) { notes.filterNot { it.done } + notes.filter { it.done } }

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

        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            ordered.forEachIndexed { visibleIndex, note ->
                // Moves act on the stored list, not on this display order, or an
                // item would jump somewhere the user did not point at.
                val storedIndex = notes.indexOfFirst { it.id == note.id }
                NoteRow(
                    note = note,
                    canMoveUp = storedIndex > 0,
                    canMoveDown = storedIndex in 0 until notes.lastIndex,
                    onToggle = { onToggle(note.id) },
                    onDelete = { onDelete(note.id) },
                    onMoveUp = { onMove(storedIndex, storedIndex - 1) },
                    onMoveDown = { onMove(storedIndex, storedIndex + 1) },
                )
                if (visibleIndex != ordered.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

@Composable
private fun NoteRow(
    note: Note,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
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
        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(R.string.notes_move_up),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.notes_move_down),
                modifier = Modifier.size(20.dp),
            )
        }
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
