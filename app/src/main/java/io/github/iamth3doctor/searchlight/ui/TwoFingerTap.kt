package io.github.iamth3doctor.searchlight.ui

import kotlin.math.abs

/**
 * A two-finger tap: two fingers down, neither travelling, both up again quickly, and never a
 * third. Fed the touch stream (times in ms, positions of the first two pointers); [up] says
 * whether the gesture that just ended was one. Pure; JVM-tested.
 */
class TwoFingerTap(private val timeoutMs: Long, private val slopPx: Float) {
    private var maxPointers = 0
    private var secondDownAt = -1L
    private val start = FloatArray(4)
    private var travelled = false

    fun down() {
        maxPointers = 1
        secondDownAt = -1L
        travelled = false
    }

    /** Another finger down; [count] fingers are down now. */
    fun pointerDown(count: Int, timeMs: Long, x0: Float, y0: Float, x1: Float, y1: Float) {
        maxPointers = maxOf(maxPointers, count)
        if (count == 2 && secondDownAt < 0) {
            secondDownAt = timeMs
            start[0] = x0; start[1] = y0; start[2] = x1; start[3] = y1
        }
    }

    fun move(count: Int, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (secondDownAt < 0 || count < 2) return
        if (abs(x0 - start[0]) > slopPx || abs(y0 - start[1]) > slopPx || abs(x1 - start[2]) > slopPx || abs(y1 - start[3]) > slopPx) {
            travelled = true
        }
    }

    /** The last finger lifted: was it a two-finger tap? */
    fun up(timeMs: Long): Boolean =
        maxPointers == 2 && secondDownAt >= 0 && !travelled && timeMs - secondDownAt <= timeoutMs
}
