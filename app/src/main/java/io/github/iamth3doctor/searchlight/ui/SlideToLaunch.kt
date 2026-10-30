package io.github.iamth3doctor.searchlight.ui

/**
 * Hold, slide, go: after the hold that opens the favorites row, the same finger can slide along
 * it; the slot under the finger is the selection, and lifting there launches it. Pure; JVM-tested.
 *
 * - The finger never reached the row: lifting leaves the row open, for tapping as usual.
 * - It reached the row and lifts over an app: launch that app.
 * - It reached the row, then lifts off it (or over an empty slot, or the touch is cancelled):
 *   nothing launches and the row closes.
 */
class SlideToLaunch {

    sealed class Outcome {
        object StayOpen : Outcome()
        data class Launch(val slot: Int) : Outcome()
        object Dismiss : Outcome()
    }

    /** The highlighted slot (one with an app under the finger), or null. */
    var selected: Int? = null
        private set

    /** Has the finger been over the row since the hold? */
    var entered = false
        private set

    /**
     * The finger moved: [slot] is the slot under it (null off the row), [hasApp] whether that
     * slot holds an app. Returns true when the selection changed (time for a tick and a redraw).
     */
    fun move(slot: Int?, hasApp: Boolean): Boolean {
        if (slot != null) entered = true
        val next = slot?.takeIf { hasApp }
        if (next == selected) return false
        selected = next
        return true
    }

    /** The finger lifted (over [slot], null off the row) or the touch was [cancelled]. */
    fun release(slot: Int?, hasApp: Boolean, cancelled: Boolean = false): Outcome {
        val outcome = when {
            !entered -> Outcome.StayOpen
            !cancelled && slot != null && hasApp -> Outcome.Launch(slot)
            else -> Outcome.Dismiss
        }
        selected = null
        entered = false
        return outcome
    }

    companion object {
        /**
         * Which of the row's slots is under ([x], [y]), all in screen pixels: inside the row's
         * band from [top] to [bottom] and between a slot's [lefts] and [rights]. Null elsewhere.
         */
        fun slotAt(x: Float, y: Float, top: Int, bottom: Int, lefts: IntArray, rights: IntArray): Int? {
            if (y < top || y >= bottom) return null
            for (i in lefts.indices) if (x >= lefts[i] && x < rights[i]) return i
            return null
        }
    }
}
