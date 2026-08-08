package com.example.timbertimer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.data.model.Project
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.TreeSpecies

/**
 * A project's display name.
 *
 * The two built-ins are stored in English so a record means the same thing on
 * every client and in every language; only those untouched defaults are shown
 * translated. A project deleted elsewhere has no name left to show.
 */
@Composable
fun projectLabel(project: Project): String = when {
    project.missing -> stringResource(R.string.project_none)
    project.id == Projects.REST_ID && project.name == Projects.REST_NAME ->
        stringResource(R.string.project_rest_name)

    project.id == Projects.DEFAULT_ID && project.name == Projects.DEFAULT_NAME ->
        stringResource(R.string.project_default_name)

    else -> project.name
}

/** The colour disc that stands for a project wherever there is no room for its name. */
@Composable
fun ProjectDot(project: Project, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(projectColors(project).base)
    )
}

/** The project's name on a wash of its own colour — the website's chip. */
@Composable
fun ProjectChip(project: Project, modifier: Modifier = Modifier) {
    val colors = projectColors(project)
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(6.dp),
        color = colors.soft,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            ProjectDot(project, size = 8.dp)
            Spacer(Modifier.width(5.dp))
            Text(
                text = projectLabel(project),
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The project a session or a record belongs to.
 *
 * A record can still point at a project that no longer exists; that one stays
 * selectable so opening its editor does not silently move it somewhere else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectPicker(
    book: ProjectBook,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: String = stringResource(R.string.field_project),
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = book[selectedId]
    val options = remember(book.all, selectedId) {
        if (book.contains(selectedId)) book.all else book.all + selected
    }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = projectLabel(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            singleLine = true,
            label = { Text(label) },
            leadingIcon = { ProjectDot(selected, size = 14.dp) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded && enabled) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { project ->
                DropdownMenuItem(
                    leadingIcon = { ProjectDot(project, size = 12.dp) },
                    text = {
                        Text(
                            projectLabel(project),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    trailingIcon = {
                        if (project.id == selectedId) {
                            Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(18.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(project.id)
                    },
                )
            }
        }
    }
}

/** The sixteen swatches a project's colour is chosen from. */
@Composable
fun ColorGrid(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val perRow = 8
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Projects.COLORS.chunked(perRow).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { color ->
                    val isSelected = color.equals(selected, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(solidColor(color))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .selectable(
                                selected = isSelected,
                                role = Role.RadioButton,
                                onClick = { onSelect(color) },
                            )
                            .semantics { contentDescription = color },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
                // Keeps a short last row's swatches the same width as the rest.
                repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Species are chosen by looking at them, not by reading a list of names, and
 * they are drawn in the colour they will actually be planted in.
 */
@Composable
fun SpeciesRow(
    selected: String,
    color: String,
    onSelect: (TreeSpecies) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    includeWilted: Boolean = false,
) {
    val palette = rememberTreePalette(color)
    val species = remember(includeWilted) {
        if (includeWilted) TreeSpecies.choosable + TreeSpecies.WILTED else TreeSpecies.choosable
    }

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(species, key = { it.id }) { item ->
            val isSelected = item.id == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(item) },
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
            ) {
                TreeArt(
                    species = item,
                    palette = palette,
                    modifier = Modifier.size(44.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(item.displayRes),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
