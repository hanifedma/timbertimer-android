package com.example.timbertimer.core

import com.example.timbertimer.data.model.FocusRecord

/**
 * When the forest last had something happen to it, or null if it never has.
 *
 * This is what the idle notification counts up from, and it is easy to get
 * subtly wrong in two directions at once — so it lives here, as a plain
 * function over a list, where [com.example.timbertimer.TimerLogicTest] can pin
 * all three of its rules down without a device.
 *
 * **A rest counts.** It is a record like any other and it is time the user
 * spent in the app; a clock that skipped rests would tell someone who stopped
 * resting ten minutes ago that they had been away for three days.
 *
 * **A planned session does not.** The calendar can block out a session in the
 * future, and a plan is not a thing already done — counting one would run the
 * clock backwards, or park it at zero until the plan came around.
 *
 * **The answer never runs ahead of the clock.** A record's length is rounded to
 * whole minutes, so one that has only just been saved can end up to a minute
 * from now. Judging a record by when it *began* is what lets it count straight
 * away; clamping the answer to [now] is what stops it reading as negative.
 */
fun lastActivityEndedAt(records: List<FocusRecord>, now: Long): Long? =
    records
        .asSequence()
        .filter { it.startedAt in 1..now }
        .map { minOf(it.endsAt, now) }
        .maxOrNull()
