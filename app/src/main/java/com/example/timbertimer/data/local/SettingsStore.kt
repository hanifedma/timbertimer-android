package com.example.timbertimer.data.local

import android.content.Context
import com.example.timbertimer.data.model.Limits
import com.example.timbertimer.data.model.TimerMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * How the end of a rest announces itself.
 *
 * Deliberately its own setting rather than a reading of the two switches above
 * it. Those govern a *cue* — the chime a finished focus session ends on, which
 * is pleasant to have and no loss to miss. This governs an *alarm*: the whole
 * reason a rest has a length is that the user intends to be pulled out of it,
 * and someone who silences the chime while they work has said nothing about
 * whether they want to be woken from a break.
 */
enum class RestAlertStyle(val wire: String) {
    BOTH("both"),
    SOUND("sound"),
    VIBRATE("vibrate"),

    /**
     * The notification alone, and the default.
     *
     * A rest alarm that makes a noise nobody asked for is the kind of thing an
     * app gets uninstalled over, so the loud versions are opted into rather
     * than out of. The notification is still the stubborn one — full-screen on
     * a locked phone, and undismissable until it is answered — so a rest still
     * ends visibly on a default install; it just does not shout.
     */
    SILENT("silent");

    val wantsSound: Boolean get() = this == BOTH || this == SOUND
    val wantsVibration: Boolean get() = this == BOTH || this == VIBRATE

    companion object {
        fun from(value: String?): RestAlertStyle =
            entries.firstOrNull { it.wire == value } ?: SILENT
    }
}

/**
 * Per-device preferences: appearance, sound, and the small pieces of state that
 * make the app feel like it remembers you — the last session name, the duration
 * you usually pick, and which tree each session name grows.
 *
 * Exposed as [StateFlow]s so Compose repaints the moment one changes, and backed
 * by SharedPreferences so the values are there on the first frame.
 */
class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val json = Json { ignoreUnknownKeys = true }

    // ---------- appearance ----------

    private val _themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString(KEY_THEME, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
        _themeMode.value = mode
    }

    // ---------- sound ----------

    private val _soundEnabled = MutableStateFlow(prefs.getBoolean(KEY_SOUND, true))
    val soundEnabled: StateFlow<Boolean> = _soundEnabled.asStateFlow()

    fun setSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SOUND, enabled).apply()
        _soundEnabled.value = enabled
    }

    /** 0f..2f, matching the web app's 0-200% slider. */
    private val _soundVolume = MutableStateFlow(prefs.getFloat(KEY_VOLUME, 0.8f).coerceIn(0f, 2f))
    val soundVolume: StateFlow<Float> = _soundVolume.asStateFlow()

    fun setSoundVolume(volume: Float) {
        val safe = volume.coerceIn(0f, 2f)
        prefs.edit().putFloat(KEY_VOLUME, safe).apply()
        _soundVolume.value = safe
    }

    private val _vibrate = MutableStateFlow(prefs.getBoolean(KEY_VIBRATE, true))
    val vibrate: StateFlow<Boolean> = _vibrate.asStateFlow()

    fun setVibrate(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_VIBRATE, enabled).apply()
        _vibrate.value = enabled
    }

    /**
     * The quiet nudge that appears when the app is left with nothing running.
     * On by default, because it is the whole point of a habit app — but it is a
     * notification, so it gets a switch.
     */
    private val _idleReminder = MutableStateFlow(prefs.getBoolean(KEY_IDLE_REMINDER, true))
    val idleReminder: StateFlow<Boolean> = _idleReminder.asStateFlow()

    fun setIdleReminder(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IDLE_REMINDER, enabled).apply()
        _idleReminder.value = enabled
    }

    /**
     * Keeps the app alive in the background so the live socket stays connected.
     *
     * Without it the process only exists while a timer runs or a screen is open,
     * which is why a task ticked on one device could sit unnoticed in another
     * device's widget for hours. The cost is a permanent low-priority
     * notification, which the platform requires in exchange, so it gets a switch.
     */
    private val _backgroundSync = MutableStateFlow(prefs.getBoolean(KEY_BACKGROUND_SYNC, true))
    val backgroundSync: StateFlow<Boolean> = _backgroundSync.asStateFlow()

    fun setBackgroundSync(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BACKGROUND_SYNC, enabled).apply()
        _backgroundSync.value = enabled
    }

    /**
     * The standing count of today's rests, kept in the shade.
     *
     * It is deliberately hard to get rid of: ongoing, and put straight back if
     * something removes it anyway — the point is a number that is simply always
     * there, not one that survives until the next time the shade is swept.
     *
     * Which is exactly why it needs a switch. A notification that cannot be
     * dismissed and has no off switch is not a feature, it is something wrong
     * with the phone; this is the off switch, and it is the only thing that
     * stops the notification coming back.
     */
    private val _restTally = MutableStateFlow(prefs.getBoolean(KEY_REST_TALLY, true))
    val restTally: StateFlow<Boolean> = _restTally.asStateFlow()

    fun setRestTally(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REST_TALLY, enabled).apply()
        _restTally.value = enabled
    }

    // ---------- timer defaults ----------

    private val _timerMode = MutableStateFlow(TimerMode.from(prefs.getString(KEY_TIMER_MODE, null)))
    val timerMode: StateFlow<TimerMode> = _timerMode.asStateFlow()

    fun setTimerMode(mode: TimerMode) {
        prefs.edit().putString(KEY_TIMER_MODE, mode.wire).apply()
        _timerMode.value = mode
    }

    private val _duration = MutableStateFlow(
        prefs.getInt(KEY_DURATION, Limits.DEFAULT_DURATION).coerceIn(1, Limits.TIMER_MINUTES_MAX)
    )
    val duration: StateFlow<Int> = _duration.asStateFlow()

    fun setDuration(minutes: Int) {
        val safe = minutes.coerceIn(1, Limits.TIMER_MINUTES_MAX)
        prefs.edit().putInt(KEY_DURATION, safe).apply()
        _duration.value = safe
    }

    // ---------- rest defaults ----------

    /**
     * Whether the rest panel counts down to a length or simply runs.
     *
     * A countdown is the default now: a rest you have to remember to end is the
     * one that quietly becomes an hour, which is the whole reason the shortcuts
     * exist. The open-ended stopwatch is still one tap away for anyone who
     * preferred it, and is what an upgrade finds mid-rest.
     */
    private val _restMode = MutableStateFlow(TimerMode.from(prefs.getString(KEY_REST_MODE, null)))
    val restMode: StateFlow<TimerMode> = _restMode.asStateFlow()

    fun setRestMode(mode: TimerMode) {
        prefs.edit().putString(KEY_REST_MODE, mode.wire).apply()
        _restMode.value = mode
    }

    private val _restDuration = MutableStateFlow(
        prefs.getInt(KEY_REST_DURATION, Limits.DEFAULT_REST_DURATION)
            .coerceIn(1, Limits.TIMER_MINUTES_MAX)
    )
    val restDuration: StateFlow<Int> = _restDuration.asStateFlow()

    fun setRestDuration(minutes: Int) {
        val safe = minutes.coerceIn(1, Limits.TIMER_MINUTES_MAX)
        prefs.edit().putInt(KEY_REST_DURATION, safe).apply()
        _restDuration.value = safe
    }

    private val _restAlert = MutableStateFlow(
        RestAlertStyle.from(prefs.getString(KEY_REST_ALERT, null))
    )
    val restAlert: StateFlow<RestAlertStyle> = _restAlert.asStateFlow()

    fun setRestAlert(style: RestAlertStyle) {
        prefs.edit().putString(KEY_REST_ALERT, style.wire).apply()
        _restAlert.value = style
    }

    private val _sessionName = MutableStateFlow(prefs.getString(KEY_SESSION_NAME, null).orEmpty())
    val sessionName: StateFlow<String> = _sessionName.asStateFlow()

    fun setSessionName(name: String) {
        val safe = name.trim().take(Limits.TITLE_MAX).ifBlank { Limits.DEFAULT_TITLE }
        prefs.edit().putString(KEY_SESSION_NAME, safe).apply()
        _sessionName.value = safe
    }

    /** True until the user has focused once, so startup can seed the name from history. */
    fun hasSessionName(): Boolean = !prefs.getString(KEY_SESSION_NAME, null).isNullOrBlank()

    // ---------- the chosen project ----------

    /**
     * Which project the next session will be filed under. Empty until something
     * has been chosen, so startup can pick the project of the last session
     * instead of forcing the default one.
     */
    private val _selectedProjectId = MutableStateFlow(prefs.getString(KEY_PROJECT, null).orEmpty())
    val selectedProjectId: StateFlow<String> = _selectedProjectId.asStateFlow()

    fun setSelectedProjectId(id: String) {
        prefs.edit().putString(KEY_PROJECT, id).apply()
        _selectedProjectId.value = id
    }

    // ---------- task ↔ project ----------

    /**
     * A task name belongs to a project: track "wash dishes" under Errands once,
     * and choosing that task picks Errands again by itself.
     *
     * What was last chosen on *this* device wins. Failing that the answer comes
     * from history, which syncs — so the pairing still follows the account onto
     * another device. Keyed by the lowercased name, exactly as the web app keys
     * its own map, so both clients agree on what a name means.
     */
    fun projectForTask(title: String): String? = taskProjects()[prefKey(title)]

    fun rememberTaskProject(title: String, projectId: String) {
        val key = prefKey(title)
        val current = taskProjects()
        if (current[key] == projectId) return
        val updated = current.toMutableMap().apply { put(key, projectId) }
        prefs.edit()
            .putString(KEY_TASK_PROJECTS, json.encodeToString(MAP_SERIALIZER, updated))
            .apply()
    }

    private fun taskProjects(): Map<String, String> = readMap(KEY_TASK_PROJECTS)

    /**
     * The per-name tree choices made by the version before projects existed.
     *
     * Read only: they are folded into the projects those names became, the first
     * time a project has to be invented for an old record.
     */
    fun legacyTreePreference(name: String): String? = readMap(KEY_TREE_PREFS)[prefKey(name)]

    // ---------- calendar ----------

    private val _calendarDays = MutableStateFlow(
        prefs.getInt(KEY_CAL_DAYS, CALENDAR_DEFAULT_DAYS).coerceIn(1, CALENDAR_MAX_DAYS)
    )
    val calendarDays: StateFlow<Int> = _calendarDays.asStateFlow()

    fun setCalendarDays(days: Int) {
        val safe = days.coerceIn(1, CALENDAR_MAX_DAYS)
        prefs.edit().putInt(KEY_CAL_DAYS, safe).apply()
        _calendarDays.value = safe
    }

    /** Calendar zoom, in density-independent pixels per hour. */
    private val _calendarZoom = MutableStateFlow(
        prefs.getFloat(KEY_CAL_ZOOM, CALENDAR_DEFAULT_ZOOM)
            .coerceIn(CALENDAR_MIN_ZOOM, CALENDAR_MAX_ZOOM)
    )
    val calendarZoom: StateFlow<Float> = _calendarZoom.asStateFlow()

    fun setCalendarZoom(zoom: Float) {
        val safe = zoom.coerceIn(CALENDAR_MIN_ZOOM, CALENDAR_MAX_ZOOM)
        prefs.edit().putFloat(KEY_CAL_ZOOM, safe).apply()
        _calendarZoom.value = safe
    }

    private fun readMap(key: String): Map<String, String> {
        val raw = prefs.getString(key, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(MAP_SERIALIZER, raw) }.getOrElse { emptyMap() }
    }

    private fun prefKey(name: String): String =
        name.trim().lowercase().ifEmpty { "deep focus" }

    companion object {
        const val CALENDAR_MIN_ZOOM = 26f
        const val CALENDAR_MAX_ZOOM = 240f
        const val CALENDAR_DEFAULT_ZOOM = 64f
        const val CALENDAR_ZOOM_STEP = 1.3f
        const val CALENDAR_DEFAULT_DAYS = 3
        const val CALENDAR_MAX_DAYS = 7

        private const val PREFS_NAME = "timbertimer-settings"
        private const val KEY_THEME = "theme"
        private const val KEY_SOUND = "sound-enabled"
        private const val KEY_VOLUME = "sound-volume"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_IDLE_REMINDER = "idle-reminder"
        private const val KEY_BACKGROUND_SYNC = "background-sync"
        private const val KEY_REST_TALLY = "rest-tally-notification"
        private const val KEY_TIMER_MODE = "timer-mode"
        private const val KEY_DURATION = "duration"
        private const val KEY_REST_MODE = "rest-mode"
        private const val KEY_REST_DURATION = "rest-duration"
        private const val KEY_REST_ALERT = "rest-alert"
        private const val KEY_SESSION_NAME = "session-name"
        private const val KEY_TREE_PREFS = "tree-prefs"
        private const val KEY_PROJECT = "selected-project"
        private const val KEY_TASK_PROJECTS = "task-projects"
        private const val KEY_CAL_DAYS = "calendar-days"
        private const val KEY_CAL_ZOOM = "calendar-zoom"

        private val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
