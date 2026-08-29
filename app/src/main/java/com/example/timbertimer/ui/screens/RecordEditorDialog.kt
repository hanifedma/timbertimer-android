package com.example.timbertimer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.ui.RecordEditor
import com.example.timbertimer.ui.components.ProjectPicker
import com.example.timbertimer.ui.components.ClockFormat
import com.example.timbertimer.ui.components.currentLocale
import com.example.timbertimer.ui.components.rememberClockFormat
import java.util.Locale

/**
 * Add or correct a session by hand.
 *
 * The record's length is the gap between its start and its end. That is what
 * the calendar edits, and both are set with the platform's own date and time
 * pickers, so there is nothing to mistype.
 */
@Composable
fun RecordEditorDialog(
    editor: RecordEditor,
    book: ProjectBook,
    isValid: Boolean,
    onChange: (RecordEditor) -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val locale = currentLocale()
    val clock = rememberClockFormat()
    val minuteUnit = stringResource(R.string.unit_m)
    val hourUnit = stringResource(R.string.unit_h)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (editor.id == null) R.string.dialog_add_session
                    else R.string.dialog_edit_session
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ProjectPicker(
                    book = book,
                    selectedId = editor.projectId,
                    onSelect = { onChange(editor.copy(projectId = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = editor.title,
                    onValueChange = { onChange(editor.copy(title = it.take(Limits.TITLE_MAX))) },
                    label = { Text(stringResource(R.string.field_task_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                InstantField(
                    label = stringResource(R.string.field_started),
                    millis = editor.startedAt,
                    locale = locale,
                    clock = clock,
                    // Now that the start can be moved on its own, it is a field
                    // that can put the record out of order — so it says so too,
                    // rather than leaving the end looking like the only culprit.
                    isError = editor.endsBeforeStart || editor.tooLong,
                    // Sets the start, and only the start.
                    //
                    // This used to carry the end along to preserve the length,
                    // on the reading that a corrected start means "it actually
                    // began an hour later". But the dialog shows both ends and a
                    // duration underneath, so the end field silently moving is
                    // an edit the user did not make and cannot see the reason
                    // for — and it makes the obvious way to fix a start time
                    // that ran long impossible: correcting the start would push
                    // the end out by exactly as much. The web app has always
                    // behaved this way; this brings the phone into line.
                    onChange = { started -> onChange(editor.copy(startedAt = started)) },
                )

                InstantField(
                    label = stringResource(R.string.field_ended),
                    millis = editor.endedAt,
                    locale = locale,
                    clock = clock,
                    isError = editor.endsBeforeStart || editor.tooLong,
                    onChange = { onChange(editor.copy(endedAt = it)) },
                )

                Text(
                    text = when {
                        editor.endsBeforeStart -> stringResource(R.string.dialog_end_before_start)
                        editor.tooLong -> stringResource(R.string.dialog_too_long)
                        else -> stringResource(
                            R.string.metric_focused_text,
                            Time.formatMinutes(editor.minutes, minuteUnit, hourUnit),
                        )
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isValid) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )

                if (onDelete != null) {
                    Spacer(Modifier.height(2.dp))
                    TextButton(onClick = onDelete) {
                        Text(
                            stringResource(R.string.action_delete),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSave, enabled = isValid) {
                Text(stringResource(R.string.btn_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_cancel)) }
        },
    )
}

/** A date button and a time button that together edit one instant. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstantField(
    label: String,
    millis: Long,
    locale: Locale,
    clock: ClockFormat,
    onChange: (Long) -> Unit,
    isError: Boolean = false,
) {
    var pickingDate by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Row {
            OutlinedButton(
                onClick = { pickingDate = true },
                modifier = Modifier.weight(1.4f),
            ) {
                Text(Time.dateLabel(millis, locale), maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { pickingTime = true },
                modifier = Modifier.weight(1f),
            ) {
                Text(clock.time(millis), maxLines = 1)
            }
        }
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = Time.toUtcDateMillis(millis))
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Time.withDateFromUtcMillis(millis, it)) }
                    pickingDate = false
                }) { Text(stringResource(R.string.btn_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (pickingTime) {
        val state = rememberTimePickerState(
            initialHour = Time.hourOf(millis),
            initialMinute = Time.minuteOf(millis),
            is24Hour = clock.is24Hour,
        )
        AlertDialog(
            onDismissRequest = { pickingTime = false },
            title = { Text(label) },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    onChange(Time.withTime(millis, state.hour, state.minute))
                    pickingTime = false
                }) { Text(stringResource(R.string.btn_save)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingTime = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}
