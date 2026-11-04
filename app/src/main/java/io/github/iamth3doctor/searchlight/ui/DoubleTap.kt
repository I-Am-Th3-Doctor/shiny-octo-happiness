package io.github.iamth3doctor.searchlight.ui

import kotlin.math.abs

/**
 * Two taps close together in time and place make a double tap. Feed it each completed tap
 * (finger down and up without travelling, not a long press); it says when a tap finishes a
 * double tap, then starts over (a third tap begins a new pair). Pure; JVM-tested.
 */
class DoubleTap(private val timeoutMs: Long, private val slopPx: Float) {
    private var lastTime = Long.MIN_VALUE
    private var lastX = 0f
    private var lastY = 0f

    fun tap(x: Float, y: Float, timeMs: Long): Boolean {
        val second = lastTime != Long.MIN_VALUE && timeMs - lastTime in 0..timeoutMs &&
            abs(x - lastX) <= slopPx && abs(y - lastY) <= slopPx
        if (second) {
            lastTime = Long.MIN_VALUE
            return true
        }
        lastTime = timeMs
        lastX = x
        lastY = y
        return false
    }

    /** Something else happened in between (a swipe, a long press): the next tap starts afresh. */
    fun reset() {
        lastTime = Long.MIN_VALUE
    }
}
