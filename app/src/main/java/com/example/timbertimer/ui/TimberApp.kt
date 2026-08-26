package com.example.timbertimer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.timbertimer.MainActivity
import com.example.timbertimer.R
import com.example.timbertimer.core.Time
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.NoteList
import com.example.timbertimer.ui.components.currentLocale
import com.example.timbertimer.ui.components.rememberClockFormat
import com.example.timbertimer.ui.screens.CalendarScreen
import com.example.timbertimer.ui.screens.FocusScreen
import com.example.timbertimer.ui.screens.ForestScreen
import com.example.timbertimer.ui.screens.ProjectEditorDialog
import com.example.timbertimer.ui.screens.ProjectsDialog
import com.example.timbertimer.ui.screens.RecordEditorDialog
import com.example.timbertimer.ui.screens.RecordsScreen
import com.example.timbertimer.ui.screens.SettingsScreen
import com.example.timbertimer.ui.screens.TasksScreen

/** Wide enough for a side rail instead of a bottom bar. */
private const val RAIL_WIDTH_DP = 600

/** Wide enough to put the dial and its controls side by side. */
private const val TWO_COLUMN_WIDTH_DP = 840

/** Below this, the window is a phone in landscape and vertical space is scarce. */
private const val COMPACT_HEIGHT_DP = 480

private enum class Destination(
    @StringRes val label: Int,
    val icon: ImageVector,
) {
    FOCUS(R.string.nav_focus, Icons.Filled.Timer),
    CALENDAR(R.string.nav_calendar, Icons.Filled.CalendarMonth),
    FOREST(R.string.nav_forest, Icons.Filled.Park),
    TASKS(R.string.nav_tasks, Icons.Filled.CheckCircle),
    RECORDS(R.string.nav_records, Icons.AutoMirrored.Filled.List),
    SETTINGS(R.string.nav_settings, Icons.Filled.Settings),
}

/**
 * The app shell.
 *
 * Navigation moves to a side rail once there is width for it, which is what
 * makes the same build feel native on a phone, a folded tablet and a landscape
 * screen without a second layout to maintain. Content is capped in width so a
 * 12-inch tablet does not stretch a form field across the whole panel.
 *
 * Settings is deliberately not one of the five tabs: a bottom bar stops reading
 * as a row of destinations somewhere around the sixth. It lives in the top bar,
 * and joins the bar only in the one window shape that has neither — narrow *and*
 * short, which in practice means a small split-screen pane.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimberApp(
    viewModel: TimberViewModel,
    language: String,
    onLanguageChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onAddWidget: () -> Unit,
    onAddTodayWidget: () -> Unit,
    onIgnoreBatteryOptimisation: () -> Unit,
    onAllowDoNotDisturb: () -> Unit,
    onAllowFullScreen: () -> Unit,
) {
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }

    var destination by rememberSaveable { mutableStateOf(Destination.FOCUS) }
    var pendingDelete by remember { mutableStateOf<FocusRecord?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }
    var managingProjects by rememberSaveable { mutableStateOf(false) }

    val form by viewModel.form.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val rest by viewModel.rest.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val todayNotesAnchor by viewModel.todayNotesAnchor.collectAsStateWithLifecycle()
    val grove by viewModel.grove.collectAsStateWithLifecycle()
    val calendar by viewModel.calendar.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val projectEditor by viewModel.projectEditor.collectAsStateWithLifecycle()
    val pendingMove by viewModel.pendingMove.collectAsStateWithLifecycle()
    val projectsSyncBlocked by viewModel.projectsSyncBlocked.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val dataMode by viewModel.dataMode.collectAsStateWithLifecycle()

    val requestedDestination by viewModel.requestedDestination.collectAsStateWithLifecycle()
    val themeMode by viewModel.settings.themeMode.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.settings.soundEnabled.collectAsStateWithLifecycle()
    val volume by viewModel.settings.soundVolume.collectAsStateWithLifecycle()
    val vibrate by viewModel.settings.vibrate.collectAsStateWithLifecycle()
    val idleReminder by viewModel.settings.idleReminder.collectAsStateWithLifecycle()
    val backgroundSync by viewModel.settings.backgroundSync.collectAsStateWithLifecycle()
    val restAlert by viewModel.settings.restAlert.collectAsStateWithLifecycle()

    // Midnight rolling past is what makes the Today list "new and blank"
    // without any explicit reset — but only for someone who was actually
    // looking at today live; see checkTodayNotesRollover.
    LaunchedEffect(now) { viewModel.checkTodayNotesRollover() }

    // The widget opens the app at a screen rather than wherever it was left.
    LaunchedEffect(requestedDestination) {
        when (requestedDestination) {
            MainActivity.DESTINATION_TASKS -> destination = Destination.TASKS
            MainActivity.DESTINATION_FOCUS -> destination = Destination.FOCUS
            else -> return@LaunchedEffect
        }
        viewModel.consumeDestination()
    }

    // Messages are resource ids, so they land in whatever language is current
    // at the moment they are shown rather than when they were raised.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { message ->
            val text = if (message.args.isEmpty()) {
                context.getString(message.res)
            } else {
                context.getString(message.res, *message.args.toTypedArray())
            }
            snackbarHost.currentSnackbarData?.dismiss()
            snackbarHost.showSnackbar(text)
        }
    }

    // Breakpoints read straight off the configuration. Compose updates it on
    // every rotation and every resize in split screen, so the layout follows the
    // window rather than whatever shape the app happened to launch in.
    val configuration = LocalConfiguration.current
    val widthDp = configuration.screenWidthDp
    val heightDp = configuration.screenHeightDp
    val useRail = widthDp >= RAIL_WIDTH_DP
    val wideContent = widthDp >= TWO_COLUMN_WIDTH_DP
    // A phone in landscape has barely 400dp of height; a title bar that only
    // repeats the selected tab is not worth 64 of them.
    val showTopBar = heightDp >= COMPACT_HEIGHT_DP

    val barDestinations = remember(useRail, showTopBar) {
        // Settings has a home in the top bar or the rail; it only needs a tab
        // when there is neither.
        if (useRail || showTopBar) Destination.entries - Destination.SETTINGS
        else Destination.entries.toList()
    }

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = { Text(stringResource(destination.label)) },
                    actions = {
                        IconButton(onClick = { destination = Destination.SETTINGS }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = stringResource(R.string.nav_settings),
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (!useRail) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    barDestinations.forEach { item ->
                        NavigationBarItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label), maxLines = 1) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHost) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        // The scaffold's insets are applied once, here, so the rail and the content
        // sit in the same safe area and neither double-pads the other.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            if (useRail) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(8.dp))
                    Destination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item,
                            onClick = { destination = item },
                            icon = { Icon(item.icon, contentDescription = null) },
                            label = { Text(stringResource(item.label), maxLines = 1) },
                        )
                    }
                }
            }

            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter,
            ) {
                // A calendar earns every pixel of a tablet's width; a form does
                // not, so the two are capped differently.
                val maxContentWidth = if (destination == Destination.CALENDAR) 1200.dp else 900.dp
                val listPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

                Box(
                    modifier = Modifier
                        .widthIn(max = maxContentWidth)
                        .fillMaxSize(),
                ) {
                    when (destination) {
                        Destination.FOCUS -> ScrollingScreen {
                            FocusScreen(
                                form = form,
                                book = projects,
                                timer = timer,
                                rest = rest,
                                now = now,
                                suggestions = viewModel.titleSuggestions(),
                                dataMode = dataMode,
                                wide = wideContent,
                                onTitleChange = viewModel::setTitle,
                                onDurationChange = viewModel::setDuration,
                                onModeChange = viewModel::setMode,
                                onProjectChange = viewModel::setProject,
                                onManageProjects = { managingProjects = true },
                                onSpeciesChange = viewModel::setProjectTree,
                                onStart = viewModel::start,
                                onFinish = viewModel::finish,
                                onRestModeChange = viewModel::setRestMode,
                                onRestDurationChange = viewModel::setRestDuration,
                                onStartRest = viewModel::startRest,
                                onFinishRest = viewModel::finishRest,
                            )
                        }

                        Destination.CALENDAR -> CalendarScreen(
                            state = calendar,
                            records = records,
                            timer = timer,
                            now = now,
                            book = projects,
                            onShift = viewModel::shiftCalendar,
                            onToday = viewModel::calendarToToday,
                            onDaysChange = viewModel::setCalendarDays,
                            onZoom = viewModel::zoomCalendar,
                            onOpenRecord = { viewModel.openEditor(it) },
                            onCreateRecord = { startedAt, minutes ->
                                viewModel.openEditor(null, startedAt = startedAt, minutes = minutes)
                            },
                            onMoveRecord = viewModel::proposeMove,
                            onOpenTimer = { destination = Destination.FOCUS },
                        )

                        Destination.FOREST -> ForestScreen(
                            records = records,
                            book = projects,
                            view = grove.view,
                            anchor = grove.anchor,
                            onViewChange = viewModel::setGroveView,
                            onShift = viewModel::shiftGrove,
                            onCurrent = viewModel::resetGroveToCurrent,
                            contentPadding = listPadding,
                        )

                        Destination.TASKS -> ScrollingScreen {
                            val todayAnchorKey = remember(todayNotesAnchor) {
                                Time.localDateKey(todayNotesAnchor)
                            }
                            val generalNotes = remember(notes) {
                                notes.filter { it.list == NoteList.GENERAL }
                            }
                            // The repository always resolves forDate for a Today
                            // note (falling back to its created day if it came
                            // from before that column existed), so it is never
                            // null here.
                            val todayNotes = remember(notes, todayAnchorKey) {
                                notes.filter { it.list == NoteList.TODAY && it.forDate == todayAnchorKey }
                            }
                            TasksScreen(
                                generalNotes = generalNotes,
                                todayNotes = todayNotes,
                                todayAnchor = todayNotesAnchor,
                                onAdd = viewModel::addNote,
                                onToggle = viewModel::toggleNote,
                                onDelete = viewModel::deleteNote,
                                onMove = viewModel::moveNote,
                                onReorder = viewModel::reorderNotes,
                                onShiftDay = viewModel::shiftTodayNotes,
                                onBackToToday = viewModel::resetTodayNotesToToday,
                            )
                        }

                        Destination.RECORDS -> RecordsScreen(
                            records = viewModel.visibleRecords(),
                            allRecords = records,
                            book = projects,
                            query = filter.query,
                            onQueryChange = viewModel::setQuery,
                            onAdd = { viewModel.openEditor(null) },
                            onEdit = { viewModel.openEditor(it) },
                            onDelete = { pendingDelete = it },
                            contentPadding = listPadding,
                        )

                        Destination.SETTINGS -> ScrollingScreen {
                            SettingsScreen(
                                session = session,
                                dataMode = dataMode,
                                themeMode = themeMode,
                                language = language,
                                soundEnabled = soundEnabled,
                                volume = volume,
                                vibrate = vibrate,
                                idleReminder = idleReminder,
                                backgroundSync = backgroundSync,
                                restAlert = restAlert,
                                onThemeChange = viewModel.settings::setThemeMode,
                                onLanguageChange = onLanguageChange,
                                onSoundToggle = viewModel::toggleSound,
                                onVolumeChange = viewModel.settings::setSoundVolume,
                                onVolumeSettled = viewModel::previewSound,
                                onVibrateChange = viewModel.settings::setVibrate,
                                onIdleReminderChange = viewModel::setIdleReminder,
                                onBackgroundSyncChange = viewModel::setBackgroundSync,
                                onRestAlertChange = viewModel::setRestAlert,
                                onIgnoreBatteryOptimisation = onIgnoreBatteryOptimisation,
                                onAllowDoNotDisturb = onAllowDoNotDisturb,
                                onAllowFullScreen = onAllowFullScreen,
                                onAddWidget = onAddWidget,
                                onAddTodayWidget = onAddTodayWidget,
                                onManageProjects = { managingProjects = true },
                                projectsSyncBlocked = projectsSyncBlocked,
                                onSignIn = onSignIn,
                                onSignOut = viewModel::signOut,
                                onDeleteAll = { confirmDeleteAll = true },
                            )
                        }
                    }
                }
            }
        }
    }

    editor?.let { current ->
        RecordEditorDialog(
            editor = current,
            book = projects,
            isValid = viewModel.editorIsValid(current),
            onChange = viewModel::updateEditor,
            onSave = viewModel::saveEditor,
            onDelete = current.id?.let { id ->
                {
                    val record = records.firstOrNull { it.id == id }
                    viewModel.closeEditor()
                    if (record != null) pendingDelete = record
                }
            },
            onDismiss = viewModel::closeEditor,
        )
    }

    if (managingProjects) {
        ProjectsDialog(
            book = projects,
            recordCount = viewModel::recordCountFor,
            onEdit = { project ->
                viewModel.openProjectEditor(project)
            },
            onDismiss = { managingProjects = false },
        )
    }

    projectEditor?.let { current ->
        ProjectEditorDialog(
            editor = current,
            nameTaken = viewModel.projectNameIsTaken(current),
            isValid = viewModel.projectEditorIsValid(current),
            onNameChange = viewModel::setProjectEditorName,
            onColorChange = viewModel::setProjectEditorColor,
            onTreeChange = viewModel::setProjectEditorTree,
            onSave = viewModel::saveProjectEditor,
            onDelete = { current.id?.let(viewModel::deleteProject) },
            onDismiss = viewModel::closeProjectEditor,
            recordCount = current.id?.let(viewModel::recordCountFor) ?: 0,
        )
    }

    pendingMove?.let { move ->
        val locale = currentLocale()
        val clock = rememberClockFormat()
        val book = projects
        AlertDialog(
            onDismissRequest = viewModel::cancelMove,
            title = {
                Text(
                    stringResource(
                        if (move.resized) R.string.confirm_resize_record_title
                        else R.string.confirm_move_record_title
                    )
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = move.record.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    com.example.timbertimer.ui.components.ProjectChip(book[move.record.projectId])
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = Time.spanLabel(
                            move.record.startedAt,
                            maxOf(move.record.endedAt, move.record.startedAt),
                            locale,
                            clock.is24Hour,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "→  " + Time.spanLabel(
                            move.startedAt,
                            move.endedAt,
                            locale,
                            clock.is24Hour,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMove) {
                    Text(stringResource(R.string.btn_save))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelMove) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    pendingDelete?.let { record ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.confirm_delete_title)) },
            text = { Text(stringResource(R.string.confirm_delete_record, record.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteRecord(record)
                    pendingDelete = null
                }) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.account_delete_all)) },
            text = {
                Text(
                    stringResource(
                        if (session != null) R.string.confirm_delete_all_cloud
                        else R.string.confirm_delete_all_local
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllRecords()
                    confirmDeleteAll = false
                }) { Text(stringResource(R.string.btn_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) {
                    Text(stringResource(R.string.btn_cancel))
                }
            },
        )
    }
}

/**
 * The wrapper for screens that are one long column rather than a list.
 *
 * The scroll lives here, in each screen, rather than around all of them: the
 * calendar and the forest bring their own, and nesting one scroller inside
 * another is what makes a list feel like it is fighting the finger.
 */
@Composable
private fun ScrollingScreen(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
        Spacer(Modifier.height(8.dp))
    }
}
