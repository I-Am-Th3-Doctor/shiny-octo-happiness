package io.github.iamth3doctor.searchlight.ui

import kotlin.math.roundToInt

/**
 * Where a [w]×[h] panel opened by a tap at ([tapX], [tapY]) goes, all in screen pixels: its
 * top-left corner. [safeLeft]..[safeRight] × [safeTop]..[safeBottom] is the part of the screen
 * clear of the status bar, navigation bar and cutouts; the panel stays [margin] inside it.
 *
 * Centred on the tap across. Down the screen: a tap in the top third opens the panel below it, a
 * tap in the bottom third above it ([gap] clear of the finger), and one in between centred on it.
 * Then it shifts inward as far as it must to stay wholly on screen.
 */
object TapPlacement {
    fun place(
        tapX: Float, tapY: Float, w: Int, h: Int,
        safeLeft: Int, safeTop: Int, safeRight: Int, safeBottom: Int,
        margin: Int, gap: Int,
    ): Pair<Int, Int> {
        val third = (safeBottom - safeTop) / 3f
        val top = when {
            tapY < safeTop + third -> tapY + gap
            tapY > safeBottom - third -> tapY - gap - h
            else -> tapY - h / 2f
        }.roundToInt()
        val left = (tapX - w / 2f).roundToInt()
        return clamp(left, safeLeft + margin, safeRight - margin - w) to clamp(top, safeTop + margin, safeBottom - margin - h)
    }

    /** [v] within [lo]..[hi]; when the panel is bigger than the room, its start edge stays on screen. */
    private fun clamp(v: Int, lo: Int, hi: Int) = if (hi < lo) lo else v.coerceIn(lo, hi)
}
