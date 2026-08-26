package com.example.timbertimer.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Clock, calendar and ISO-8601 helpers.
 *
 * Weeks start on Sunday and every boundary is computed in the device's own zone,
 * both matching the web app — otherwise the same session could land in different
 * weeks on the phone and on the website.
 */
object Time {

    fun zone(): ZoneId = ZoneId.systemDefault()

    // ---------- durations ----------

    /**
     * `mm:ss`, widening to `h:mm:ss` past an hour.
     *
     * The website always prints minutes, so a two-hour stopwatch reads "120:34"
     * there. On a phone that is genuinely hard to read at a glance, and it is
     * presentation only — nothing about the stored record changes.
     */
    fun formatClock(seconds: Long): String {
        val safe = maxOf(0L, seconds)
        val hours = safe / 3600
        val minutes = (safe % 3600) / 60
        val secs = safe % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.US, hours, minutes, secs)
        } else {
            "%02d:%02d".format(Locale.US, minutes, secs)
        }
    }

    /** `45m`, `1h`, `1h 30m` — using the caller's localized unit labels. */
    fun formatMinutes(minutes: Int, minuteUnit: String, hourUnit: String): String {
        val rounded = maxOf(0, minutes)
        if (rounded < 60) return "$rounded$minuteUnit"
        val hours = rounded / 60
        val leftover = rounded % 60
        return if (leftover > 0) "$hours$hourUnit $leftover$minuteUnit" else "$hours$hourUnit"
    }

    // ---------- calendar ----------

    fun localDate(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(zone()).toLocalDate()

    /** Stable `yyyy-MM-dd` key in local time, used to group "today". */
    fun localDateKey(millis: Long): String = localDate(millis).toString()

    fun startOfDay(millis: Long): Long =
        localDate(millis).atStartOfDay(zone()).toInstant().toEpochMilli()

    /** Sunday-start, matching JavaScript's `getDay()`. */
    fun startOfWeek(millis: Long): Long =
        localDate(millis)
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
            .atStartOfDay(zone())
            .toInstant()
            .toEpochMilli()

    fun startOfMonth(millis: Long): Long =
        localDate(millis).withDayOfMonth(1).atStartOfDay(zone()).toInstant().toEpochMilli()

    fun addDays(millis: Long, days: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zone()).plusDays(days).toInstant().toEpochMilli()

    fun addMonths(millis: Long, months: Long): Long =
        Instant.ofEpochMilli(millis).atZone(zone()).plusMonths(months).toInstant().toEpochMilli()

    // ---------- labels ----------

    fun weekRangeLabel(startMillis: Long, locale: Locale): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d", locale)
        val start = localDate(startMillis)
        return "${formatter.format(start)} - ${formatter.format(start.plusDays(6))}"
    }

    fun monthLabel(millis: Long, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(localDate(millis))

    fun todayLabel(millis: Long, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEEE, MMMM d", locale).format(localDate(millis))

    /** "Tue, Aug 25" — compact enough for the Today to-do list's day nav. */
    fun shortDayLabel(millis: Long, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(localDate(millis))

    fun recordDateLabel(millis: Long, locale: Locale): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone())
            .format(Instant.ofEpochMilli(millis))

    // ---------- the calendar ----------

    /** "Mon", the column heading for a day. */
    fun weekdayShort(millis: Long, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEE", locale).format(localDate(millis))

    /** "08/31" under the weekday — short enough for a one-seventh-wide column. */
    fun dayAndMonth(millis: Long, locale: Locale): String =
        DateTimeFormatter.ofPattern("dd/MM", locale).format(localDate(millis))

    /**
     * The gutter's hour marks: "17" or "5 PM".
     *
     * Deliberately shorter than a full time — the gutter is barely wider than
     * the day columns can spare, and "5:00 PM" would be cut off mid-word.
     */
    fun hourLabel(hour: Int, locale: Locale, is24Hour: Boolean): String =
        DateTimeFormatter
            .ofPattern(if (is24Hour) "HH" else "h a", locale)
            .format(java.time.LocalTime.of(hour.coerceIn(0, 23), 0))

    /**
     * "14:05" or "2:05 PM" — the time alone, for a block that already sits under
     * its own date.
     *
     * [is24Hour] comes from the device setting rather than from the locale,
     * because Android lets that be overridden and every other clock on the phone
     * follows the override.
     */
    fun timeShort(millis: Long, locale: Locale, is24Hour: Boolean): String =
        DateTimeFormatter
            .ofPattern(if (is24Hour) "HH:mm" else "h:mm a", locale)
            .withZone(zone())
            .format(Instant.ofEpochMilli(millis))

    /**
     * "Sat, 8 Aug · 09:00 – 10:45" — one line naming a record's day and the
     * stretch of it the record covers, for reading a change back before it is
     * agreed to.
     */
    fun spanLabel(startMillis: Long, endMillis: Long, locale: Locale, is24Hour: Boolean): String {
        val day = DateTimeFormatter.ofPattern("EEE, d MMM", locale).format(localDate(startMillis))
        return "$day · ${timeShort(startMillis, locale, is24Hour)} – " +
            timeShort(endMillis, locale, is24Hour)
    }

    /** "Sat, 8 August" for one day, "Aug 8 – Aug 10" for a range of them. */
    fun calendarRangeLabel(startMillis: Long, days: Int, locale: Locale): String {
        val first = localDate(startMillis)
        if (days <= 1) return DateTimeFormatter.ofPattern("EEE, d MMMM", locale).format(first)
        val last = first.plusDays((days - 1).toLong())
        val sameMonth = first.month == last.month && first.year == last.year
        val startText = DateTimeFormatter.ofPattern("MMM d", locale).format(first)
        val endText = DateTimeFormatter
            .ofPattern(if (sameMonth) "d" else "MMM d", locale)
            .format(last)
        return "$startText – $endText"
    }

    /** Minutes past local midnight, which is what the calendar lays out against. */
    fun minutesIntoDay(millis: Long): Int {
        val dayStart = startOfDay(millis)
        return ((millis - dayStart) / 60_000L).coerceIn(0L, 1440L).toInt()
    }

    // ---------- editing an instant ----------

    /** "31 Jul 2026" — the date alone, beside its own time button. */
    fun dateLabel(millis: Long, locale: Locale): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(localDate(millis))

    fun hourOf(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(zone()).hour

    fun minuteOf(millis: Long): Int =
        Instant.ofEpochMilli(millis).atZone(zone()).minute

    /**
     * Material's date picker reports a calendar date as midnight *UTC*, so it has
     * to be read back in UTC and then re-applied to the local day — reading it in
     * the device's own zone would land on the day before for anyone east of
     * Greenwich.
     */
    fun withDateFromUtcMillis(millis: Long, utcDateMillis: Long): Long {
        val date = Instant.ofEpochMilli(utcDateMillis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
        val time = Instant.ofEpochMilli(millis).atZone(zone()).toLocalTime()
        return date.atTime(time).atZone(zone()).toInstant().toEpochMilli()
    }

    /** The same instant's date, as the midnight-UTC value the picker expects. */
    fun toUtcDateMillis(millis: Long): Long =
        localDate(millis).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    fun withTime(millis: Long, hour: Int, minute: Int): Long =
        Instant.ofEpochMilli(millis)
            .atZone(zone())
            .withHour(hour.coerceIn(0, 23))
            .withMinute(minute.coerceIn(0, 59))
            .withSecond(0)
            .withNano(0)
            .toInstant()
            .toEpochMilli()

    /** `2026-07-31 14:05` for the editable "Started" field. */
    fun editableTimestamp(millis: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .format(Instant.ofEpochMilli(millis).atZone(zone()))

    /** Parses [editableTimestamp] back, returning null when it is not valid. */
    fun parseEditableTimestamp(text: String): Long? = runCatching {
        LocalDateTime
            .parse(text.trim(), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            .atZone(zone())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()

    // ---------- ISO-8601, the database's format ----------

    /**
     * Postgres hands `timestamptz` back as `...+00:00` while the web client
     * writes `...Z`; both have to parse, and a bare local timestamp is read as
     * UTC rather than thrown away.
     */
    fun parseIso(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching {
                LocalDateTime.parse(value).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
            }
            .getOrNull()
    }

    /** UTC ISO-8601, the same shape JavaScript's `toISOString()` produces. */
    fun toIso(millis: Long): String = Instant.ofEpochMilli(millis).toString()
}
