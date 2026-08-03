package com.example.timbertimer.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.runtime.rememberCoroutineScope
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
import com.example.timbertimer.data.local.LocaleStore
import com.example.timbertimer.data.local.ThemeMode
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.ui.screens.FocusScreen
import com.example.timbertimer.ui.screens.ForestScreen
import com.example.timbertimer.ui.screens.RecordEditorDialog
import com.example.timbertimer.ui.screens.RecordsScreen
import com.example.timbertimer.ui.screens.SettingsScreen
import com.example.timbertimer.ui.screens.TasksScreen
import kotlinx.coroutines.launch

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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimberApp(
    viewModel: TimberViewModel,
    language: String,
    onLanguageChange: (String) -> Unit,
    onOpenAuthUrl: (String) -> Unit,
    onAddWidget: () -> Unit,
    onIgnoreBatteryOptimisation: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }

    var destination by rememberSaveable { mutableStateOf(Destination.FOCUS) }
    var pendingDelete by remember { mutableStateOf<FocusRecord?>(null) }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    val form by viewModel.form.collectAsStateWithLifecycle()
    val timer by viewModel.timer.collectAsStateWithLifecycle()
    val rest by viewModel.rest.collectAsStateWithLifecycle()
    val now by viewModel.now.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val grove by viewModel.grove.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val dataMode by viewModel.dataMode.collectAsStateWithLifecycle()

    val requestedDestination by viewModel.requestedDestination.collectAsStateWithLifecycle()
    val themeMode by viewModel.settings.themeMode.collectAsStateWithLifecycle()
    val soundEnabled by viewModel.settings.soundEnabled.collectAsStateWithLifecycle()
    val volume by viewModel.settings.soundVolume.collectAsStateWithLifecycle()
    val vibrate by viewModel.settings.vibrate.collectAsStateWithLifecycle()
    val idleReminder by viewModel.settings.idleReminder.collectAsStateWithLifecycle()
    val backgroundSync by viewModel.settings.backgroundSync.collectAsStateWithLifecycle()

    // The widget opens the app at a screen rather than wherever it was left.
    LaunchedEffect(requestedDestination) {
        when (requestedDestination) {
            MainActivity.DESTINATION_TASKS -> destination = Destination.TASKS
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

    Scaffold(
        topBar = {
            if (showTopBar) {
                TopAppBar(
                    title = { Text(stringResource(destination.label)) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (!useRail) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    Destination.entries.forEach { item ->
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
                    Spacer16()
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
                Column(
                    modifier = Modifier
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    when (destination) {
                        Destination.FOCUS -> FocusScreen(
                            form = form,
                            timer = timer,
                            rest = rest,
                            now = now,
                            suggestions = viewModel.titleSuggestions(),
                            dataMode = dataMode,
                            wide = wideContent,
                            onTitleChange = viewModel::setTitle,
                            onDurationChange = viewModel::setDuration,
                            onModeChange = viewModel::setMode,
                            onSpeciesChange = viewModel::setSpecies,
                            onStart = viewModel::start,
                            onFinish = viewModel::finish,
                            onStartRest = viewModel::startRest,
                            onFinishRest = viewModel::finishRest,
                        )

                        Destination.FOREST -> ForestScreen(
                            records = records,
                            view = grove.view,
                            anchor = grove.anchor,
                            onViewChange = viewModel::setGroveView,
                            onShift = viewModel::shiftGrove,
                            onCurrent = viewModel::resetGroveToCurrent,
                        )

                        Destination.TASKS -> TasksScreen(
                            notes = notes,
                            onAdd = viewModel::addNote,
                            onToggle = viewModel::toggleNote,
                            onDelete = viewModel::deleteNote,
                            onReorder = viewModel::reorderNotes,
                        )

                        Destination.RECORDS -> RecordsScreen(
                            records = viewModel.visibleRecords(),
                            allRecords = records,
                            query = filter.query,
                            status = filter.status,
                            onQueryChange = viewModel::setQuery,
                            onStatusChange = viewModel::setStatusFilter,
                            onAdd = { viewModel.openEditor(null) },
                            onEdit = { viewModel.openEditor(it) },
                            onDelete = { pendingDelete = it },
                        )

                        Destination.SETTINGS -> SettingsScreen(
                            session = session,
                            dataMode = dataMode,
                            themeMode = themeMode,
                            language = language,
                            soundEnabled = soundEnabled,
                            volume = volume,
                            vibrate = vibrate,
                            idleReminder = idleReminder,
                            backgroundSync = backgroundSync,
                            onThemeChange = viewModel.settings::setThemeMode,
                            onLanguageChange = onLanguageChange,
                            onSoundToggle = viewModel::toggleSound,
                            onVolumeChange = viewModel.settings::setSoundVolume,
                            onVolumeSettled = viewModel::previewSound,
                            onVibrateChange = viewModel.settings::setVibrate,
                            onIdleReminderChange = viewModel::setIdleReminder,
                            onBackgroundSyncChange = viewModel::setBackgroundSync,
                            onIgnoreBatteryOptimisation = onIgnoreBatteryOptimisation,
                            onAddWidget = onAddWidget,
                            onSignIn = {
                                scope.launch {
                                    val url = viewModel.authorizeUrl()
                                    if (url != null) onOpenAuthUrl(url)
                                }
                            },
                            onSignOut = viewModel::signOut,
                            onDeleteAll = { confirmDeleteAll = true },
                        )
                    }

                    Spacer16()
                }
            }
        }
    }

    editor?.let { current ->
        RecordEditorDialog(
            editor = current,
            isValid = viewModel.editorIsValid(current),
            onChange = viewModel::updateEditor,
            onSave = viewModel::saveEditor,
            onDismiss = viewModel::closeEditor,
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

@Composable
private fun Spacer16() {
    Spacer(Modifier.height(8.dp))
}
