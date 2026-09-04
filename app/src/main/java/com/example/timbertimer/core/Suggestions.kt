package com.example.timbertimer.core

import com.example.timbertimer.data.model.FocusRecord

/**
 * The task names offered while the user types one.
 *
 * The web app hands this to the browser as a `<datalist>` and gets the
 * filtering for free. Compose has no such thing, so the same behaviour is
 * spelled out here — and spelled out in plain Kotlin, with no Android import,
 * so the part with the actual decisions in it can be checked in a test rather
 * than by typing into a phone.
 */
object Suggestions {

    /**
     * How many the menu offers at once.
     *
     * A cap rather than the whole history: the menu covers the screen below the
     * field, and a list nobody will scroll to the end of is not more helpful
     * than a short one. Typing narrows it long before this matters.
     */
    const val LIMIT = 12

    /**
     * Every name used before, newest first, each appearing once.
     *
     * Rests are left out. "Rest" is not a task anyone types, and offering it
     * would push real names out of a list this short.
     *
     * Compared case-insensitively but kept as written: someone who typed "Deep
     * Focus" last week and "deep focus" yesterday meant one task, and the
     * spelling they used most recently is the one they are most likely to want
     * back.
     */
    fun history(records: List<FocusRecord>): List<String> =
        records
            .asSequence()
            .filterNot { it.isRest }
            .sortedByDescending { it.startedAt }
            .map { it.title.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
            .toList()

    /**
     * The names worth showing for what has been typed so far.
     *
     * Anything containing the text matches, which is what a browser's datalist
     * does — "deploy" should find "Friday deploy". But a name that *starts*
     * with it is almost always the one being reached for, so those come first
     * rather than being buried under the middle-of-the-word matches.
     *
     * A name identical to what is already typed is dropped. It is not a
     * suggestion at that point, it is the field's own contents read back, and
     * leaving it in means the menu hangs around with one useless row in it
     * after every pick.
     */
    fun matching(history: List<String>, query: String, limit: Int = LIMIT): List<String> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return history.take(limit)

        val leading = ArrayList<String>(limit)
        val elsewhere = ArrayList<String>(limit)

        for (title in history) {
            val haystack = title.lowercase()
            when {
                haystack == needle -> Unit
                haystack.startsWith(needle) -> leading += title
                haystack.contains(needle) -> elsewhere += title
            }
            // Both lists full is as far as this can matter: the answer is taken
            // from the front of leading, and elsewhere can only ever supply the
            // tail of it.
            if (leading.size >= limit) break
        }

        return if (leading.size >= limit) leading.take(limit)
        else (leading + elsewhere).take(limit)
    }
}
