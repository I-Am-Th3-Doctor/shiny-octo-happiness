package io.github.iamth3doctor.searchlight.ui

import kotlin.math.abs

/**
 * Counts quick taps in one place: each tap close enough in time and place to the one before adds
 * one, up to [max]; after [max], or a tap too slow or too far, the count starts again at 1. Feed it
 * each completed tap (finger down and up without travelling, not a long press). Pure; JVM-tested.
 */
class MultiTap(private val timeoutMs: Long, private val slopPx: Float, private val max: Int = 3) {
    private var count = 0
    private var lastTime = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f

    /** This tap's place in the run: 1 for a first tap, 2 for a double, 3 for a triple. */
    fun tap(x: Float, y: Float, timeMs: Long): Int {
        val follows = count in 1 until max && lastTime != Long.MIN_VALUE && timeMs - lastTime in 0..timeoutMs &&
            abs(x - lastX) <= slopPx && abs(y - lastY) <= slopPx
        count = if (follows) count + 1 else 1
        lastTime = timeMs
        lastX = x
        lastY = y
        return count
    }

    /** Something else happened in between (a swipe, a long press): the next tap starts afresh. */
    fun reset() {
        count = 0
        lastTime = Long.MIN_VALUE
    }
}
