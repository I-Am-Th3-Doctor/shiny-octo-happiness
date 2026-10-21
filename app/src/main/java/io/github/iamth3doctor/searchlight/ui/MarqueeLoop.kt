package io.github.iamth3doctor.searchlight.ui

import kotlin.math.roundToInt

/**
 * One marquee cycle: hold at the start, glide to the end at a steady speed, hold at the end,
 * then jump back. Pure, so the timing is JVM-tested.
 */
class MarqueeLoop(private val overflowPx: Int, pxPerSecond: Float) {
    private val scrollMs = (overflowPx * 1000f / pxPerSecond).roundToInt().coerceAtLeast(1)

    val durationMs: Long = (PAUSE_MS * 2 + scrollMs).toLong()

    /** Horizontal scroll at [timeMs] into the cycle. */
    fun offsetAt(timeMs: Long): Int {
        val t = timeMs - PAUSE_MS
        return when {
            t <= 0 -> 0
            t >= scrollMs -> overflowPx
            else -> (overflowPx * t / scrollMs).toInt()
        }
    }

    companion object {
        const val PAUSE_MS = 2000
    }
}
