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

    // ---------- timer defaults ----------

    private val _timerMode = MutableStateFlow(TimerMode.from(prefs.getString(KEY_TIMER_MODE, null)))
    val timerMode: StateFlow<TimerMode> = _timerMode.asStateFlow()

    fun setTimerMode(mode: TimerMode) {
        prefs.edit().putString(KEY_TIMER_MODE, mode.wire).apply()
        _timerMode.value = mode
    }

    private val _duration = MutableStateFlow(
        prefs.getInt(KEY_DURATION, Limits.DEFAULT_DURATION).coerceIn(1, Limits.MINUTES_MAX)
    )
    val duration: StateFlow<Int> = _duration.asStateFlow()

    fun setDuration(minutes: Int) {
        val safe = minutes.coerceIn(1, Limits.MINUTES_MAX)
        prefs.edit().putInt(KEY_DURATION, safe).apply()
        _duration.value = safe
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

    // ---------- remembered tree per session name ----------

    /**
     * "eating ayam" should keep growing the palm you once chose for it. Keyed by
     * the lowercased name, exactly as the web app keys its own preference map,
     * so the two clients agree on what a name means.
     */
    fun treePreference(name: String): String? = treePrefs()[prefKey(name)]

    fun saveTreePreference(name: String, speciesId: String) {
        val updated = treePrefs().toMutableMap().apply { put(prefKey(name), speciesId) }
        prefs.edit()
            .putString(KEY_TREE_PREFS, json.encodeToString(MAP_SERIALIZER, updated))
            .apply()
    }

    private fun treePrefs(): Map<String, String> {
        val raw = prefs.getString(KEY_TREE_PREFS, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(MAP_SERIALIZER, raw) }.getOrElse { emptyMap() }
    }

    private fun prefKey(name: String): String =
        name.trim().lowercase().ifEmpty { "deep focus" }

    private companion object {
        const val PREFS_NAME = "timbertimer-settings"
        const val KEY_THEME = "theme"
        const val KEY_SOUND = "sound-enabled"
        const val KEY_VOLUME = "sound-volume"
        const val KEY_VIBRATE = "vibrate"
        const val KEY_IDLE_REMINDER = "idle-reminder"
        const val KEY_BACKGROUND_SYNC = "background-sync"
        const val KEY_TIMER_MODE = "timer-mode"
        const val KEY_DURATION = "duration"
        const val KEY_SESSION_NAME = "session-name"
        const val KEY_TREE_PREFS = "tree-prefs"

        val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
