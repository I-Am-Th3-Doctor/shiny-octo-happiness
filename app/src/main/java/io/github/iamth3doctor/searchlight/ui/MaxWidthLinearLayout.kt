package io.github.iamth3doctor.searchlight.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import io.github.iamth3doctor.searchlight.R

/**
 * A LinearLayout that never grows wider than `app:maxWidth`. On the folded cover screen it
 * fills the width; unfolded it becomes a centered column. Because it measures on every pass,
 * it adapts to fold/unfold without the activity being recreated.
 */
class MaxWidthLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    private val maxWidthPx: Int

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.MaxWidthLinearLayout)
        maxWidthPx = a.getDimensionPixelSize(R.styleable.MaxWidthLinearLayout_maxWidth, Int.MAX_VALUE)
        a.recycle()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = View.MeasureSpec.getSize(widthMeasureSpec)
        val spec = if (size > maxWidthPx) {
            View.MeasureSpec.makeMeasureSpec(maxWidthPx, View.MeasureSpec.getMode(widthMeasureSpec))
        } else {
            widthMeasureSpec
        }
        super.onMeasure(spec, heightMeasureSpec)
    }
}
