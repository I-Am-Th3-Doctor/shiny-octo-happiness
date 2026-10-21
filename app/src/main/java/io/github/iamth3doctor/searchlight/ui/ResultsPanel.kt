package io.github.iamth3doctor.searchlight.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import kotlin.math.min

/** The search results panel: as tall as its results, but never more than 70% of the screen (then it scrolls inside). */
class ResultsPanel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (rootView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels) * MAX_SCREEN_FRACTION
        val size = MeasureSpec.getSize(heightMeasureSpec)
        val spec = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> MeasureSpec.makeMeasureSpec(cap.toInt(), MeasureSpec.AT_MOST)
            else -> MeasureSpec.makeMeasureSpec(min(size, cap.toInt()), MeasureSpec.getMode(heightMeasureSpec))
        }
        super.onMeasure(widthMeasureSpec, spec)
    }

    private companion object {
        const val MAX_SCREEN_FRACTION = 0.7f
    }
}
