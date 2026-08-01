package com.example.timbertimer.core

import androidx.annotation.StringRes

/**
 * Something to show the user, carried as a resource id rather than a finished
 * string.
 *
 * The repository and the timer engine both raise these from background work,
 * where there is no guarantee the right locale is in scope — and the user can
 * change language while a message is in flight. Resolving at display time is
 * what keeps the wording honest.
 */
data class UiMessage(
    @StringRes val res: Int,
    val args: List<Any> = emptyList(),
    /** Distinguishes repeats of the same message so a re-send still shows. */
    val id: Long = nextId(),
) {
    companion object {
        private var counter = 0L

        @Synchronized
        private fun nextId(): Long = ++counter

        fun of(@StringRes res: Int, vararg args: Any) = UiMessage(res, args.toList())
    }
}
