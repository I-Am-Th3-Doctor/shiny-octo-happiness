package io.github.iamth3doctor.searchlight.ui

/**
 * Where a popup menu goes next to the view it belongs to, in px, all in one coordinate space:
 * below the anchor when it fits there, else above it when it fits there, else on whichever side
 * has more room, cut to that room (the menu then scrolls). Never outside [bounds]: the window
 * minus the system bars, cutout and keyboard, minus a margin. Pure; JVM-tested.
 */
object MenuPlacement {

    class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    class Result(val x: Int, val y: Int, val width: Int, val height: Int, val above: Boolean)

    fun place(anchor: Box, bounds: Box, width: Int, naturalHeight: Int, gap: Int): Result {
        val w = width.coerceAtMost(bounds.width).coerceAtLeast(0)
        val x = (anchor.left + (anchor.width - w) / 2).coerceIn(bounds.left, bounds.right - w)
        val below = (bounds.bottom - (anchor.bottom + gap)).coerceAtLeast(0)
        val above = ((anchor.top - gap) - bounds.top).coerceAtLeast(0)
        val up = naturalHeight > below && (naturalHeight <= above || above > below)
        val room = if (up) above else below
        // No real room on either side (the anchor fills the window): over the anchor, inside the bounds.
        if (room < naturalHeight && room < bounds.height / 4) {
            val h = naturalHeight.coerceAtMost(bounds.height)
            val y = (anchor.bottom + gap).coerceIn(bounds.top, bounds.bottom - h)
            return Result(x, y, w, h, above = false)
        }
        val h = naturalHeight.coerceAtMost(room)
        val y = if (up) anchor.top - gap - h else anchor.bottom + gap
        return Result(x, y, w, h, up)
    }
}
