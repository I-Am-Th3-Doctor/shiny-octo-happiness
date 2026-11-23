package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * A small spinner: an open arc in [color] that turns while shown. It turns by the clock, not an
 * animator, so it moves (and draws) whatever the system's animation setting.
 */
class SpinnerView(context: Context) : View(context) {
    var color: Int = 0xFF000000.toInt()
        set(v) { field = v; paint.color = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        paint.strokeWidth = s * 0.12f
        val inset = paint.strokeWidth / 2f
        val l = (width - s) / 2f + inset
        val t = (height - s) / 2f + inset
        box.set(l, t, l + s - 2 * inset, t + s - 2 * inset)
        val turn = (SystemClock.uptimeMillis() % TURN_MS) / TURN_MS.toFloat() * 360f
        canvas.drawArc(box, turn - 90f, 270f, false, paint)
        if (isShown) postInvalidateOnAnimation()
    }

    private companion object {
        const val TURN_MS = 900L
    }
}
