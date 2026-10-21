package io.github.iamth3doctor.searchlight.ui

import android.animation.ValueAnimator
import android.content.Context
import android.text.TextUtils
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import kotlin.math.ceil

/**
 * One line of tile text. If it's wider than the view it scrolls in a loop ([MarqueeLoop]:
 * hold 2 s, glide, hold 2 s), with both edges faded while there's more text past them. Text
 * that fits stays put. With animations turned off in system settings it ellipsizes instead.
 *
 * Unlike the platform marquee, this needs no focus/selection and pauses at both ends.
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextView(context, attrs) {

    // Set at the end of construction: TextView's constructor calls the overrides below first.
    private var ready = false
    private var animator: ValueAnimator? = null
    private var overflowPx = 0
    private val update = Runnable { update() }

    init {
        isSingleLine = true
        isHorizontalFadingEdgeEnabled = true
        setFadingEdgeLength(context.px(R.dimen.space_m))
        ready = true
    }

    private fun schedule() {
        if (!ready) return
        removeCallbacks(update)
        post(update)
    }

    private fun update() {
        val wanted = if (ValueAnimator.areAnimatorsEnabled()) null else TextUtils.TruncateAt.END
        if (ellipsize != wanted) {
            stop()
            ellipsize = wanted // relayouts, which schedules another update
            return
        }
        val layout = layout
        val overflow = if (wanted != null || layout == null || windowVisibility != View.VISIBLE) 0
            else ceil(layout.getLineWidth(0)).toInt() - (width - compoundPaddingLeft - compoundPaddingRight)
        if (overflow <= 0) {
            stop()
            return
        }
        if (overflow == overflowPx && animator?.isRunning == true) return // same text width: keep going
        stop()
        overflowPx = overflow
        val loop = MarqueeLoop(overflow, context.pxF(R.dimen.marquee_speed))
        animator = ValueAnimator.ofInt(0, loop.durationMs.toInt()).apply {
            duration = loop.durationMs
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { scrollTo(loop.offsetAt((it.animatedValue as Int).toLong()), 0) }
            start()
        }
    }

    private fun stop() {
        animator?.cancel()
        animator = null
        overflowPx = 0
        scrollTo(0, 0)
    }

    override fun onTextChanged(text: CharSequence?, start: Int, lengthBefore: Int, lengthAfter: Int) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        schedule()
    }

    // TextView scrolls its text back into view before drawing after a change; while looping,
    // keep our position instead of flashing back to the start.
    override fun onPreDraw(): Boolean {
        val x = scrollX
        val result = super.onPreDraw()
        if (animator != null && scrollX != x) scrollTo(x, 0)
        return result
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        schedule()
    }

    // Only animate while the home screen is showing; re-check the animation setting on return.
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        schedule()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(update)
        stop()
        super.onDetachedFromWindow()
    }
}
