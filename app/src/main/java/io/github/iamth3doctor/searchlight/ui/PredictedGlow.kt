package io.github.iamth3doctor.searchlight.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import io.github.iamth3doctor.searchlight.R

/**
 * The predicted favorite's mark: a soft, warm gold halo behind its icon, as the icon's
 * slot's background. Centered on [icon] (a direct child of that slot) and sized from it, scale
 * included, so it grows with the icon when the slot is highlighted. It fades out [extent] px
 * beyond the icon's edge: enough to read on the dark panel, short of the next slot's icon.
 * The slot's parent (and its card) must not clip children, or the halo is cut at the slot's edge.
 */
class PredictedGlow(private val icon: View, private val extent: Float) : Drawable() {

    private val gold = icon.context.getColor(R.color.predicted_glow)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        if (icon.width == 0) return
        val cx = icon.left + icon.width / 2f
        val cy = icon.top + icon.height / 2f
        val inner = icon.width / 2f * icon.scaleX
        val outer = inner + extent * icon.scaleX
        val edge = inner / outer
        val clear = gold and 0x00FFFFFF
        paint.shader = RadialGradient(
            cx, cy, outer,
            intArrayOf(gold, gold, withAlpha(gold, 0.45f), clear),
            floatArrayOf(0f, edge, edge + (1 - edge) * 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, outer, paint)
    }

    private fun withAlpha(c: Int, f: Float) = ((((c ushr 24) and 0xFF) * f).toInt() shl 24) or (c and 0x00FFFFFF)

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {
        /** Puts the halo behind [icon], under [slot]'s own (pressed-state) background. */
        fun apply(slot: View, icon: View) {
            val glow = PredictedGlow(icon, slot.resources.getDimension(R.dimen.size_predicted_glow))
            slot.background = LayerDrawable(listOfNotNull(glow, slot.background).toTypedArray())
        }

        /** Does [slot] carry the halo? (for tests) */
        fun isOn(slot: View): Boolean = (slot.background as? LayerDrawable)?.let { l ->
            (0 until l.numberOfLayers).any { l.getDrawable(it) is PredictedGlow }
        } == true
    }
}
