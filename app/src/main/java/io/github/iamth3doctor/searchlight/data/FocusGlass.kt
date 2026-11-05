package io.github.iamth3doctor.searchlight.data

/**
 * Focus wears clear glass: what entering and leaving Focus, and moving the Glass transparency
 * slider, do to that one setting. Pure; JVM-tested; Prefs keeps the [State].
 *
 * - Entering (off → on): the current level is saved and the glass goes to [CLEAR]. Already
 *   in Focus (something saved): nothing changes, so the saved level is never the clear one.
 * - Leaving: the saved level comes back exactly, unless the slider was moved while in Focus
 *   (then that newer level stays).
 * - The slider outside Focus just sets the level, as always.
 */
object FocusGlass {

    const val CLEAR = 0

    /** [level]: the setting now. [saved]: the level before Focus (null outside it). */
    data class State(val level: Int, val saved: Int?, val changedInFocus: Boolean)

    fun enter(s: State): State = if (s.saved != null) s else State(CLEAR, s.level, false)

    fun exit(s: State): State = State(if (s.saved != null && !s.changedInFocus) s.saved else s.level, null, false)

    /** The slider moved to [value]. */
    fun userSet(s: State, value: Int): State = State(value, s.saved, s.changedInFocus || s.saved != null)
}
