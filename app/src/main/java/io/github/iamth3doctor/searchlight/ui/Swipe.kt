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
}
