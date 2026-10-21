package io.github.iamth3doctor.searchlight.ui

import kotlin.math.abs

/**
 * Is a finger movement a deliberate vertical swipe? Far enough ([minDistance]) and clearly more
 * vertical than horizontal, so the tiles' sideways swipes and small wobbles during a long-press
 * never count. Pure; JVM-tested.
 */
object Swipe {
    enum class Direction { UP, DOWN }

    fun vertical(dx: Float, dy: Float, minDistance: Float): Direction? = when {
        abs(dy) < minDistance || abs(dy) < 2 * abs(dx) -> null
        dy < 0 -> Direction.UP
        else -> Direction.DOWN
    }
}
