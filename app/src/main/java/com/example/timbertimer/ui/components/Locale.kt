package com.example.timbertimer.ui.components

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The locale the UI is currently drawn in.
 *
 * Read from the configuration rather than `Locale.getDefault()` so it follows
 * the in-app language override, which only exists on the activity's context.
 */
@Composable
fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        configuration.locales[0]
    } else {
        @Suppress("DEPRECATION")
        configuration.locale
    }
}
