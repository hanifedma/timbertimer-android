package com.example.timbertimer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.ui.ProjectEditor
import com.example.timbertimer.ui.components.ColorGrid
import com.example.timbertimer.ui.components.ProjectDot
import com.example.timbertimer.ui.components.SpeciesRow
import com.example.timbertimer.ui.components.TreeArt
import com.example.timbertimer.ui.components.projectColors
import com.example.timbertimer.ui.components.projectLabel
import com.example.timbertimer.ui.components.rememberTreePalette

/**
 * The list of projects, with how much history each one carries.
 *
 * Tapping one opens its editor; the two built-ins can be recoloured and given a
 * different tree like any other, they just cannot be deleted.
 */
@Composable
fun ProjectsDialog(
    book: ProjectBook,
    recordCount: (String) -> Int,
    onEdit: (Project?) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.project_manage)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                book.all.forEach { project ->
                    ProjectRow(
                        project = project,
                        count = recordCount(project.id),
                        onClick = { onEdit(project) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEdit(null) }) {
                Icon(Icons.Filled.Add, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.project_new))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.btn_close)) }
        },
    )
}

@Composable
private fun ProjectRow(project: Project, count: Int, onClick: () -> Unit) {
    val colors = projectColors(project)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .background(colors.soft)
            .padding(horizontal = 10.dp, vertical = 10.dp),
    ) {
        ProjectDot(project, size = 12.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = projectLabel(project),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = pluralStringResource(R.plurals.project_records, count, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Create or edit one project: a name, a colour, a tree, and a live preview of
 * what its forest will look like.
 *
 * A project being created follows its name — type "Reading" and both the colour
 * and the species are chosen for you — until either is picked by hand.
 */
@Composable
fun ProjectEditorDialog(
    editor: ProjectEditor,
    nameTaken: Boolean,
    isValid: Boolean,
    onNameChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onTreeChange: (TreeSpecies) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    recordCount: Int,
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    val palette = rememberTreePalette(editor.color)
    val colors = projectColors(editor.color)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (editor.isNew) R.string.project_new else R.string.project_edit))
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.soft),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        TreeArt(
                            species = TreeSpecies.byId(editor.tree) ?: TreeSpecies.PINE,
                            palette = palette,
                            modifier = Modifier.size(56.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = editor.name.ifBlank { stringResource(R.string.project_new) },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }

                OutlinedTextField(
                    value = editor.name,
                    onValueChange = onNameChange,
                    label = { Text(stringResource(R.string.project_name)) },
                    singleLine = true,
                    isError = nameTaken,
                    supportingText = if (nameTaken) {
                        { Text(stringResource(R.string.project_name_taken)) }
                    } else null,
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = stringResource(R.string.project_color),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ColorGrid(selected = editor.color, onSelect = onColorChange)

                Text(
                    text = stringResource(R.string.field_tree),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // The wilted sprout is offered everywhere, as it is on the
                // website: Rest grows one by default, and a project that has
                // been given one has to be able to show what it already is.
                SpeciesRow(
                    selected = editor.tree,
                    color = editor.color,
                    onSelect = onTreeChange,
                    includeWilted = true,
                )

                if (!editor.isNew && !editor.isBuiltIn) {
                    Spacer(Modifier.height(2.dp))
                    TextButton(onClick = { confirmingDelete = true }) {
                        Text(
                            stringResource(R.string.project_delete),
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

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.project_delete)) },
            text = {
                Text(
                    stringResource(
                        R.string.confirm_delete_project,
                        editor.name,
                        recordCount,
                        stringResource(R.string.project_default_name),
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}
