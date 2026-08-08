package com.example.timbertimer.ui.components

import android.os.Build
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.example.timbertimer.core.Time
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

/**
 * How this device writes a clock time.
 *
 * The locale decides the order and the separator; the *device setting* decides
 * 12- or 24-hour, because Android lets that be overridden and every other clock
 * on the phone follows the override. Reading only the locale would leave the
 * calendar as the one screen still saying "PM" after the user turned it off.
 */
@Composable
fun rememberClockFormat(): ClockFormat {
    val locale = currentLocale()
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    return remember(locale, is24Hour) { ClockFormat(locale, is24Hour) }
}

class ClockFormat(private val locale: Locale, val is24Hour: Boolean) {
    /** "17" or "5 PM" — the gutter's hour marks. */
    fun hour(hour: Int): String = Time.hourLabel(hour, locale, is24Hour)

    /** "14:05" or "2:05 PM". */
    fun time(millis: Long): String = Time.timeShort(millis, locale, is24Hour)
}
