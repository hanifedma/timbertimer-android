package com.example.timbertimer.core

import com.example.timbertimer.data.local.TodayTotals
import com.example.timbertimer.data.local.WidgetProjectTotal
import com.example.timbertimer.data.model.FocusRecord
import com.example.timbertimer.data.model.ProjectBook

/**
 * How a day adds up, per project.
 *
 * Kept here, free of any Android import, for the same reason the calendar's
 * layout is: the awkward parts are the day boundary and what counts as a rest,
 * and both are far easier to pin down in a plain JVM test than by watching a
 * home screen widget.
 */

/**
 * Today's records folded into one row per project, longest first.
 *
 * A record counts towards the day it *started* on — see [FocusRecord.filedAt].
 * A session that ran through midnight belongs to the evening it began, in full;
 * none of it is carried over into the next morning's figures.
 *
 * Rest is a project like any other here, so it appears in the list with its own
 * colour and its minutes count towards the total. It is *also* counted
 * separately as [TodayTotals.rests]: how many rests were taken is a different
 * question from how long they ran, and the notification asks the first one.
 */
fun todayTotals(
    records: List<FocusRecord>,
    projects: ProjectBook,
    today: String,
): TodayTotals {
    val todays = records.filter { Time.localDateKey(it.filedAt) == today }

    val totals = todays
        .groupBy { it.projectId }
        .map { (projectId, rows) ->
            val project = projects[projectId]
            WidgetProjectTotal(
                id = projectId,
                name = project.name,
                color = project.color,
                minutes = rows.sumOf { it.minutes },
            )
        }
        // Longest first, then by name, so two projects on equal totals do not
        // swap places between one redraw and the next.
        .sortedWith(compareByDescending<WidgetProjectTotal> { it.minutes }.thenBy { it.name })

    return TodayTotals(
        dateKey = today,
        projects = totals,
        rests = todays.count { it.isRest },
    )
}
