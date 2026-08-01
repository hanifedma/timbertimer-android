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

    fun recordDateLabel(millis: Long, locale: Locale): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(locale)
            .withZone(zone())
            .format(Instant.ofEpochMilli(millis))

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
