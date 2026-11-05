package io.github.iamth3doctor.searchlight.focus

/**
 * What Focus does to apps and to Do Not Disturb. Pure; JVM-tested. FocusDnd and the home screen
 * apply it.
 *
 * Apps: in Focus only allowed apps show (drawer, search, favorites), whichever restriction is
 * chosen; with [Restriction.BLOCKED] a restricted app opened from one of Searchlight's own
 * surfaces gets the "Paused during focus" screen instead. Searchlight itself is never blocked.
 *
 * Do Not Disturb: entering saves the interruption filter and turns DND on (priority only, so the
 * person's own exceptions apply: starred contacts, repeat callers, alarms); leaving puts the
 * saved filter back. Saved only on the way in, so entering again can't overwrite it. Without
 * policy access nothing is changed (Focus still works).
 */
object FocusRules {

    enum class Restriction { BLOCKED, HIDDEN }

    /** Does [packageName] show in the drawer, search and favorites now? */
    fun shows(packageName: String, focus: Boolean, allowed: Set<String>): Boolean = !focus || packageName in allowed

    /** Is opening [packageName] from Searchlight intercepted with the "Paused during focus" screen? */
    fun blocks(packageName: String, focus: Boolean, allowed: Set<String>, restriction: Restriction, self: String): Boolean =
        focus && restriction == Restriction.BLOCKED && packageName !in allowed && packageName != self

    /** No filter saved. */
    const val NONE = -1

    /** What to do: set the filter to [set] (null: leave it), and keep [saved] (NONE: nothing saved). */
    data class Dnd(val set: Int?, val saved: Int)

    /**
     * Focus on. [current] is the interruption filter now; [dndOn] the "priority only" filter.
     * Already saved (entered before): nothing changes. Without access: nothing changes, nothing
     * is saved (so granting access later still turns DND on).
     */
    fun dndEnter(current: Int, saved: Int, hasAccess: Boolean, dndOn: Int, allOff: Int): Dnd = when {
        saved != NONE || !hasAccess -> Dnd(null, saved)
        // Already in some DND (priority, alarms, total silence): keep it as the person set it.
        current != allOff -> Dnd(null, current)
        else -> Dnd(dndOn, current)
    }

    /** Focus off: the saved filter back (when there is one and access to set it). */
    fun dndExit(saved: Int, hasAccess: Boolean): Dnd =
        if (saved == NONE || !hasAccess) Dnd(null, if (hasAccess) NONE else saved) else Dnd(saved, NONE)
}
