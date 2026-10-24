package io.github.iamth3doctor.searchlight.ui

import kotlin.math.abs

/**
 * Is a finger movement a deliberate swipe? Far enough ([minDistance]) and clearly more along
 * one axis than the other, so the tiles' sideways swipes and small wobbles during a long-press
 * never count. Pure; JVM-tested.
 */
object Swipe {
    enum class Direction { UP, DOWN, LEFT, RIGHT }

    fun vertical(dx: Float, dy: Float, minDistance: Float): Direction? = when {
        abs(dy) < minDistance || abs(dy) < 2 * abs(dx) -> null
        dy < 0 -> Direction.UP
        else -> Direction.DOWN
    }

    fun horizontal(dx: Float, dy: Float, minDistance: Float): Direction? = when {
        abs(dx) < minDistance || abs(dx) < 2 * abs(dy) -> null
        dx < 0 -> Direction.LEFT
        else -> Direction.RIGHT
    }

    /**
     * Any direction, for the home-screen gestures: the main axis has travelled [minDistance] and
     * at least [ratio] times as far as the other one.
     */
    fun dominant(dx: Float, dy: Float, minDistance: Float, ratio: Float = 1.5f): Direction? {
        val ax = abs(dx)
        val ay = abs(dy)
        return when {
            ay >= minDistance && ay >= ratio * ax -> if (dy < 0) Direction.UP else Direction.DOWN
            ax >= minDistance && ax >= ratio * ay -> if (dx < 0) Direction.LEFT else Direction.RIGHT
            else -> null
        }
    }
}

/**
 * One touch on empty home space: reports at most one swipe, the moment it's clear (no waiting
 * for the finger to lift). Up keeps the app table's stricter rule ([Swipe.vertical] over
 * [upDistance]); down, left and right are the configurable gestures ([Swipe.dominant] over
 * [distance]). Pure; JVM-tested.
 */
class SwipeTracker(private val upDistance: Float, private val distance: Float) {
    private var startX = 0f
    private var startY = 0f
    private var done = false

    fun down(x: Float, y: Float) {
        startX = x
        startY = y
        done = false
    }

    /** The swipe this move completes, or null (not yet, not a swipe, or already reported). */
    fun move(x: Float, y: Float): Swipe.Direction? {
        if (done) return null
        val dx = x - startX
        val dy = y - startY
        val d = if (Swipe.vertical(dx, dy, upDistance) == Swipe.Direction.UP) Swipe.Direction.UP
        else Swipe.dominant(dx, dy, distance)?.takeIf { it != Swipe.Direction.UP }
        if (d != null) done = true
        return d
    }
}
