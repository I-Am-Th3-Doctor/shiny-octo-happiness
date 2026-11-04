package io.github.iamth3doctor.searchlight.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.widget.ImageView
import io.github.iamth3doctor.searchlight.R

/**
 * An app icon that can carry an unread count: a small dark pill with a light hairline at its
 * top-right corner (the frosted glass's colors, legible on any wallpaper or icon). The count
 * only; "99+" past 99; nothing at 0.
 */
class BadgedImageView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ImageView(context, attrs) {

    var badgeCount: Int = 0
        set(value) {
            if (field == value) return
            field = value
            contentDescription = baseDescription?.let { describe(it) }
            invalidate()
        }

    /** What screen readers say for the icon, before the count is added to it. */
    var baseDescription: CharSequence? = null
        set(value) {
            field = value
            contentDescription = value?.let { describe(it) }
        }

    private fun describe(label: CharSequence): CharSequence =
        if (badgeCount > 0) context.resources.getQuantityString(R.plurals.badge_description, badgeCount, label, badgeCount) else label

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.badge_fill) }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.color(R.color.badge_border)
        strokeWidth = context.pxF(R.dimen.stroke_glass)
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.color(R.color.on_surface)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    private val pill = RectF()

    /** The count as drawn (tests read it). */
    val badgeText: String? get() = when {
        badgeCount <= 0 -> null
        badgeCount > 99 -> "99+"
        else -> badgeCount.toString()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val label = badgeText ?: return
        val h = (minOf(width, height) * 0.42f).coerceIn(context.pxF(R.dimen.size_badge_min), context.pxF(R.dimen.size_badge_max))
        text.textSize = h * 0.62f
        val w = maxOf(h, text.measureText(label) + h * 0.6f)
        pill.set(width - w, 0f, width.toFloat(), h)
        val r = h / 2
        canvas.drawRoundRect(pill, r, r, fill)
        pill.inset(stroke.strokeWidth / 2, stroke.strokeWidth / 2)
        canvas.drawRoundRect(pill, r, r, stroke)
        val fm = text.fontMetrics
        canvas.drawText(label, pill.centerX(), pill.centerY() - (fm.ascent + fm.descent) / 2, text)
    }
}
