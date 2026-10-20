package io.github.iamth3doctor.searchlight.ui

import android.content.Context
import android.util.AttributeSet
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * The app block in search results: a vertical grid that is exactly as tall as its first
 * [maxRows] rows (so 4 × 2 = 8 apps folded are visible) and scrolls for the rest. With fewer
 * apps it shrinks to fit: never an empty row. Row heights are measured, not assumed, so two-line
 * names and large font sizes fit.
 */
class AppGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : RecyclerView(context, attrs) {

    var maxRows = 2

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        // Lay out as tall as the content wants, then cap at the bottom of row [maxRows].
        super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val lm = layoutManager as? GridLayoutManager ?: return
        val visible = lm.spanCount * maxRows
        var bottom = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (getChildAdapterPosition(child) < visible) bottom = maxOf(bottom, lm.getDecoratedBottom(child))
        }
        if (bottom > 0) {
            val cap = bottom + paddingBottom
            if (measuredHeight > cap) setMeasuredDimension(measuredWidth, cap)
        }
    }
}
