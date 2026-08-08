package com.example.timbertimer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.timbertimer.R
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.model.ActiveTimer
import com.example.timbertimer.data.model.DataMode
import com.example.timbertimer.data.model.ProjectBook
import com.example.timbertimer.data.model.Projects
import com.example.timbertimer.data.model.RestTimer
import com.example.timbertimer.data.model.TimerMode
import com.example.timbertimer.data.model.TreeSpecies
import com.example.timbertimer.ui.FocusForm
import com.example.timbertimer.ui.components.Panel
import com.example.timbertimer.ui.components.ProjectChip
import com.example.timbertimer.ui.components.ProjectPicker
import com.example.timbertimer.ui.components.SegmentedRow
import com.example.timbertimer.ui.components.SpeciesRow
import com.example.timbertimer.ui.components.TimerDial
import com.example.timbertimer.ui.components.TreeArt
import com.example.timbertimer.ui.components.projectColors
import com.example.timbertimer.ui.components.projectLabel
import com.example.timbertimer.ui.components.rememberTreePalette

private val DURATION_PRESETS = listOf(15, 25, 45, 60)

/**
 * The focus screen.
 *
 * On a narrow phone the dial sits above its controls; given the width of a
 * tablet or a landscape phone, the two sit side by side so neither has to
 * scroll. [wide] is the only thing that differs between them.
 */
@Composable
fun FocusScreen(
    form: FocusForm,
    book: ProjectBook,
    timer: ActiveTimer?,
    rest: RestTimer?,
    now: Long,
    suggestions: List<String>,
    dataMode: DataMode,
    wide: Boolean,
    onTitleChange: (String) -> Unit,
    onDurationChange: (Int) -> Unit,
    onModeChange: (TimerMode) -> Unit,
    onProjectChange: (String) -> Unit,
    onManageProjects: () -> Unit,
    onSpeciesChange: (TreeSpecies) -> Unit,
    onStart: () -> Unit,
    onFinish: () -> Unit,
    onStartRest: () -> Unit,
    onFinishRest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (wide) {
        Row(
            modifier = modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                DialPanel(form, book, timer, now, dataMode, onStart, onFinish)
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                SetupPanel(
                    form, book, timer, suggestions,
                    onTitleChange, onDurationChange, onModeChange,
                    onProjectChange, onManageProjects, onSpeciesChange,
                )
                RestPanel(book, rest, now, onStartRest, onFinishRest)
            }
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            DialPanel(form, book, timer, now, dataMode, onStart, onFinish)
            SetupPanel(
                form, book, timer, suggestions,
                onTitleChange, onDurationChange, onModeChange,
                onProjectChange, onManageProjects, onSpeciesChange,
            )
            RestPanel(book, rest, now, onStartRest, onFinishRest)
        }
    }
}

@Composable
private fun DialPanel(
    form: FocusForm,
    book: ProjectBook,
    timer: ActiveTimer?,
    now: Long,
    dataMode: DataMode,
    onStart: () -> Unit,
    onFinish: () -> Unit,
) {
    val running = timer != null
    val stopwatch = (timer?.mode ?: form.mode) == TimerMode.STOPWATCH
    val project = book[timer?.projectId ?: form.projectId]

    // Reading `now` here is what makes the whole dial repaint each second.
    val seconds = when {
        timer == null && stopwatch -> 0L
        timer == null -> form.durationMinutes * 60L
        stopwatch -> timer.elapsedSeconds(now)
        else -> timer.remainingSeconds(now)
    }
    val progress = timer?.progress(now) ?: 0f
    val growth = when {
        timer == null -> 0.08f
        stopwatch -> (timer.elapsedSeconds(now) / 3600f).coerceIn(0.08f, 1f)
        else -> progress.coerceIn(0.08f, 1f)
    }

    // The kicker says where this session will be saved rather than repeating the
    // title bar — the same thing the website puts beside its logo.
    Panel(
        kicker = stringResource(
            if (dataMode == DataMode.CLOUD) R.string.brand_cloud_garden
            else R.string.brand_local_garden
        ),
        title = form.title.ifBlank { projectLabel(project) },
    ) {
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectChip(project)
        }

        // The dial is square, and the square is capped by the *window*, not by a
        // fixed number: on a phone in landscape there are barely 400dp of height
        // to share with the clock and the buttons. Capping both sides and
        // centring is what keeps it square without spilling over either — a
        // height cap alone would be quietly ignored, because an aspect ratio
        // satisfies its width first and reports whatever height that implies.
        val dialSize = dialMaxSize()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            TimerDial(
                species = project.species,
                palette = rememberTreePalette(project),
                // The ring picks up the project's colour, so a running session
                // reads as that project at a glance.
                ringColor = projectColors(project).base,
                progress = progress,
                showRing = !stopwatch,
                growth = growth,
                clockText = Time.formatClock(seconds),
                stateLabel = stringResource(
                    if (running) R.string.timer_growing else R.string.timer_ready
                ),
                progressLabel = when {
                    !running -> null
                    // A stopwatch has no percentage to show, so it reports the
                    // minutes it has run instead — the same swap the website makes.
                    stopwatch -> "${timer!!.elapsedSeconds(now) / 60}m"
                    else -> "${(progress * 100).toInt()}%"
                },
                modifier = Modifier
                    .sizeIn(maxWidth = dialSize, maxHeight = dialSize)
                    .fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStart,
                enabled = !running,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_start))
            }
            OutlinedButton(
                onClick = onFinish,
                enabled = running,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.Stop, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.btn_finish))
            }
        }
    }
}

/**
 * How big the dial may be: at most half the window's height and, on a wide
 * screen where the panel is only half the width, never so large that the square
 * pushes its own controls off the panel. Never so small the tree is a smudge.
 */
@Composable
private fun dialMaxSize(): Dp {
    val configuration = LocalConfiguration.current
    val byHeight = configuration.screenHeightDp * 0.5f
    val byWidth = configuration.screenWidthDp * 0.6f
    return minOf(byHeight, byWidth).dp.coerceIn(160.dp, 420.dp)
}

@Composable
private fun SetupPanel(
    form: FocusForm,
    book: ProjectBook,
    timer: ActiveTimer?,
    suggestions: List<String>,
    onTitleChange: (String) -> Unit,
    onDurationChange: (Int) -> Unit,
    onModeChange: (TimerMode) -> Unit,
    onProjectChange: (String) -> Unit,
    onManageProjects: () -> Unit,
    onSpeciesChange: (TreeSpecies) -> Unit,
) {
    // Everything on this panel describes the session about to start, so a
    // running timer locks all of it — its identity is already fixed.
    val editable = timer == null
    val project = book[form.projectId]

    Panel(kicker = stringResource(R.string.field_session)) {
        SegmentedRow(
            options = listOf(TimerMode.COUNTDOWN, TimerMode.STOPWATCH),
            selected = form.mode,
            label = {
                stringResource(
                    if (it == TimerMode.COUNTDOWN) R.string.mode_countdown else R.string.mode_stopwatch
                )
            },
            onSelect = onModeChange,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        // Managing projects stays available while a session runs; only choosing
        // a different one for *this* session does not.
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProjectPicker(
                book = book,
                selectedId = form.projectId,
                onSelect = onProjectChange,
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onManageProjects) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.project_manage),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = form.title,
            onValueChange = onTitleChange,
            enabled = editable,
            singleLine = true,
            label = { Text(stringResource(R.string.field_task)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.fillMaxWidth(),
        )

        if (editable && suggestions.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(suggestions, key = { it }) { suggestion ->
                    AssistChip(
                        onClick = { onTitleChange(suggestion) },
                        label = {
                            Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.widthIn(max = 180.dp),
                    )
                }
            }
        }

        if (form.mode == TimerMode.COUNTDOWN) {
            Spacer(Modifier.height(14.dp))
            Text(
                text = stringResource(R.string.field_duration),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DURATION_PRESETS.forEach { minutes ->
                    val selected = form.durationMinutes == minutes
                    OutlinedButton(
                        onClick = { onDurationChange(minutes) },
                        enabled = editable,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 4.dp,
                            vertical = 8.dp,
                        ),
                        colors = if (selected) {
                            ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        } else ButtonDefaults.outlinedButtonColors(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("$minutes", maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            DurationField(
                minutes = form.durationMinutes,
                enabled = editable,
                onDurationChange = onDurationChange,
            )
        }

        Spacer(Modifier.height(14.dp))
        Text(
            text = stringResource(R.string.field_tree_project),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        // The tree belongs to the project, so choosing one here re-plants every
        // record that project ever grew — including the ones already in the
        // forest. That is the point: a project looks like one thing.
        SpeciesRow(
            selected = project.tree,
            color = project.color,
            onSelect = onSpeciesChange,
            enabled = !project.missing,
            // Only where it is already the answer — otherwise the row would show
            // nothing selected for a project that grows one.
            includeWilted = project.tree == TreeSpecies.WILTED.id,
        )
    }
}

/**
 * The free-text minutes box.
 *
 * It keeps its own text rather than reading straight off the model, because a
 * field bound to an Int cannot be emptied: deleting the last digit leaves
 * nothing to parse, the model refuses the change, and the old number reappears
 * under the cursor. Here the box may be briefly empty while it is being retyped,
 * and only a value that actually parses is pushed up.
 */
@Composable
private fun DurationField(
    minutes: Int,
    enabled: Boolean,
    onDurationChange: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(minutes.toString()) }

    // A preset button changes the model from outside; adopt that, but leave
    // typing alone — the text already agrees with the model in that case.
    LaunchedEffect(minutes) {
        if (text.toIntOrNull() != minutes) text = minutes.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { raw ->
            val digits = raw.filter(Char::isDigit).take(3)
            text = digits
            digits.toIntOrNull()?.let(onDurationChange)
        },
        enabled = enabled,
        singleLine = true,
        label = { Text(stringResource(R.string.field_custom)) },
        suffix = { Text(stringResource(R.string.field_minutes)) },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun RestPanel(
    book: ProjectBook,
    rest: RestTimer?,
    now: Long,
    onStartRest: () -> Unit,
    onFinishRest: () -> Unit,
) {
    val resting = rest != null
    val elapsed = rest?.elapsedSeconds(now) ?: 0L
    val restProject = book[Projects.REST_ID]

    Panel(
        kicker = stringResource(R.string.rest_elapsed),
        title = stringResource(if (resting) R.string.rest_resting else R.string.rest_title),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A rest grows the tree it will plant, so its cost is visible while
            // it is being spent rather than only afterwards.
            TreeArt(
                species = restProject.species,
                palette = rememberTreePalette(restProject),
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = Time.formatClock(elapsed),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onStartRest,
                enabled = !resting,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.rest_start), maxLines = 1)
            }
            OutlinedButton(
                onClick = onFinishRest,
                enabled = resting,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.rest_finish), maxLines = 1)
            }
        }
    }
}
