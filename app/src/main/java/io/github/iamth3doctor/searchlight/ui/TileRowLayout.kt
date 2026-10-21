package io.github.iamth3doctor.searchlight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.ViewGroup
import io.github.iamth3doctor.searchlight.R

/**
 * The unfolded home card's tiles, side by side in one row. Each tile gets at least its
 * minimumWidth (what its buttons need: Quick Actions, Music), the rest share what's left
 * ([TileWidths]); a faint hairline separates neighbours.
 */
class TileRowLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    private val divider = Paint().apply {
        color = context.color(R.color.glass_border)
        strokeWidth = context.pxF(R.dimen.stroke_glass)
    }
    private val dividerInset = context.pxF(R.dimen.space_m)

    init {
        setWillNotDraw(false)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val shown = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        val widths = TileWidths.split(width - paddingLeft - paddingRight, shown.map { it.minimumWidth })
        shown.forEachIndexed { i, child ->
            child.measure(
                MeasureSpec.makeMeasureSpec(widths[i], MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height - paddingTop - paddingBottom, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = paddingLeft
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            child.layout(x, paddingTop, x + child.measuredWidth, paddingTop + child.measuredHeight)
            x += child.measuredWidth
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        var first = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            if (!first) canvas.drawLine(child.left.toFloat(), dividerInset, child.left.toFloat(), height - dividerInset, divider)
            first = false
        }
    }
}
