package io.github.iamth3doctor.searchlight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.ViewGroup
import io.github.iamth3doctor.searchlight.R

/**
 * The unfolded home card's tiles, side by side in one row: the at-a-glance bar. Each tile gets
 * what shows it whole (its natural width, or its minimumWidth for buttons: Quick Actions);
 * a flexible tile (Music, tagged R.id.tile_flexible) takes its minimumWidth or leaves the row
 * ([TileWidths.fit]); spare width is shared; a faint hairline separates neighbours.
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
        val inner = height - paddingTop - paddingBottom
        val shown = (0 until childCount).map { getChildAt(it) }.filter { it.visibility != GONE }
        val flexible = shown.map { it.getTag(R.id.tile_flexible) == true }
        val needs = shown.mapIndexed { i, child ->
            if (flexible[i]) child.minimumWidth
            else {
                // Its content on one line, uncut.
                child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY))
                maxOf(child.minimumWidth, child.measuredWidth)
            }
        }
        val widths = TileWidths.fit(width - paddingLeft - paddingRight, needs, flexible)
        shown.forEachIndexed { i, child ->
            child.measure(MeasureSpec.makeMeasureSpec(widths[i], MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(inner, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = paddingLeft
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            // A flexible tile with no room is laid out empty (0 wide): no hairline for it either.
            child.layout(x, paddingTop, x + child.measuredWidth, paddingTop + child.measuredHeight)
            x += child.measuredWidth
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        var first = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE || child.width == 0) continue
            if (!first) canvas.drawLine(child.left.toFloat(), dividerInset, child.left.toFloat(), height - dividerInset, divider)
            first = false
        }
    }
}
