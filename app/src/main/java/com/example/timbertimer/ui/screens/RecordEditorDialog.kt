package com.example.timbertimer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.RecordStatus
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.ui.RecordEditor
import com.example.timbertimer.ui.components.SegmentedRow

/**
 * Add or correct a session by hand.
 *
 * The started-at field is plain text in `yyyy-MM-dd HH:mm`. A picker would be
 * three taps deeper for a field that is usually being nudged by a few minutes,
 * and the Save button stays disabled until what is typed actually parses.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordEditorDialog(
    editor: RecordEditor,
    isValid: Boolean,
    onChange: (RecordEditor) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    var speciesExpanded by remember { mutableStateOf(false) }

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
                OutlinedTextField(
                    value = editor.title,
                    onValueChange = { onChange(editor.copy(title = it.take(Limits.TITLE_MAX))) },
                    label = { Text(stringResource(R.string.field_session)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = editor.startedAt,
                    onValueChange = { onChange(editor.copy(startedAt = it)) },
                    label = { Text(stringResource(R.string.field_started)) },
                    supportingText = { Text("yyyy-MM-dd HH:mm") },
                    isError = !isValid && editor.startedAt.isNotBlank(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.field_status),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SegmentedRow(
                    options = listOf(RecordStatus.COMPLETED, RecordStatus.ABANDONED),
                    selected = editor.status,
                    label = {
                        stringResource(
                            if (it == RecordStatus.COMPLETED) R.string.status_completed
                            else R.string.status_abandoned
                        )
                    },
                    onSelect = { onChange(editor.copy(status = it)) },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = editor.durationMinutes,
                        onValueChange = {
                            onChange(editor.copy(durationMinutes = it.filter(Char::isDigit).take(3)))
                        },
                        label = { Text(stringResource(R.string.field_goal_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = editor.actualMinutes,
                        onValueChange = {
                            onChange(editor.copy(actualMinutes = it.filter(Char::isDigit).take(3)))
                        },
                        label = { Text(stringResource(R.string.field_actual_minutes)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                // An abandoned session always wilts, so the species is not asked
                // for — offering a choice that gets discarded would be a lie.
                if (editor.status == RecordStatus.COMPLETED) {
                    ExposedDropdownMenuBox(
                        expanded = speciesExpanded,
                        onExpandedChange = { speciesExpanded = it },
                    ) {
                        OutlinedTextField(
                            value = stringResource(editor.species.displayRes),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.field_tree)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = speciesExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(
                                    androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                ),
                        )
                        ExposedDropdownMenu(
                            expanded = speciesExpanded,
                            onDismissRequest = { speciesExpanded = false },
                        ) {
                            TreeSpecies.choosable.forEach { species ->
                                DropdownMenuItem(
                                    text = { Text(stringResource(species.displayRes)) },
                                    onClick = {
                                        onChange(editor.copy(species = species))
                                        speciesExpanded = false
                                    },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(2.dp))
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
