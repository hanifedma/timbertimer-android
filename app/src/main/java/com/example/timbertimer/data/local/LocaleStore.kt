package com.example.timbertimer.data.local

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/**
 * In-app language override, so TimberTimer can be read in Korean on an English
 * phone (or the reverse) without changing the whole device — the same choice the
 * web app offers.
 *
 * Backed by SharedPreferences rather than DataStore on purpose: the value has to
 * be read synchronously inside `attachBaseContext`, which runs before the
 * activity exists and long before any coroutine could deliver a result.
 */
class LocaleStore(context: Context) {

    // Deliberately NOT context.applicationContext: this is constructed from
    // Activity.attachBaseContext, where that property is still null.
    // SharedPreferences are keyed by name per process, so the base context
    // resolves the same file, and only the handle is retained — not the context.
    private val prefs = context.getSharedPreferences("timbertimer-locale", Context.MODE_PRIVATE)

    /** A BCP-47 tag such as "en" or "ko", or [SYSTEM] to follow the device. */
    var languageTag: String
        get() = prefs.getString(KEY_LANGUAGE, SYSTEM) ?: SYSTEM
        set(value) {
            prefs.edit().putString(KEY_LANGUAGE, value).apply()
        }

    /**
     * Returns a context whose resources resolve in the chosen language. Applied
     * in `attachBaseContext` so every string lookup — and the layout direction —
     * is already correct on the very first frame.
     */
    fun wrap(base: Context): Context {
        val tag = languageTag
        if (tag == SYSTEM) return base

        val locale = Locale.forLanguageTag(tag)
        // Also set the JVM default so java.time's localized date formats follow
        // the same choice as the string resources.
        Locale.setDefault(locale)

        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLayoutDirection(locale)
        }
        return base.createConfigurationContext(configuration)
    }

    companion object {
        /** Follow the device language. */
        const val SYSTEM = ""
        const val ENGLISH = "en"
        const val KOREAN = "ko"

        private const val KEY_LANGUAGE = "language_tag"
    }
}
