package io.github.iamth3doctor.searchlight.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Looper
import android.view.View
import androidx.annotation.ColorInt
import java.util.WeakHashMap

/**
 * Frosted glass: the wallpaper right behind [host], blurred, under a dark tint, with rounded
 * corners and an optional hairline [border] that catches the light. Without a readable wallpaper
 * (see [WallpaperBlur]) it's the [fallbackTint] alone, which is darker so text stays readable on
 * an unblurred wallpaper. Only the background: the host's content draws on top, untouched.
 *
 * With an [adaptive] layer the tint takes the wallpaper's own hue ([GlassTint]) instead of
 * [tint], which then only applies if the wallpaper's color couldn't be sampled.
 *
 * Without [wallpaper] (a screen with its own opaque background) it's always the [fallbackTint]
 * over whatever the page draws: the same glass look, nothing sampled.
 *
 * Every tint, blurred or fallback, is scaled by the "Glass transparency" setting ([level], see
 * [GlassTint.scaleAlpha]), worked out afresh on every draw. [setLevel] redraws every live glass
 * surface, in every window, at once.
 */
class FrostedDrawable(
    private val host: View,
    val radius: Float,
    @ColorInt tint: Int,
    @ColorInt fallbackTint: Int,
    @ColorInt border: Int = 0,
    borderWidth: Float = 0f,
    private val adaptive: GlassTint.Layer? = null,
    private val wallpaper: Boolean = true,
) : Drawable() {

    private val fixedTint = tint
    private val fixedFallback = fallbackTint
    private var alpha = 255

    /** The tint each path draws with at the current level (over the blur, and without one). */
    val appliedTint: Int get() {
        val sample = if (adaptive != null) WallpaperBlur.sampledColor() else null
        return if (sample == null || adaptive == null) GlassTint.scale(fixedTint, level) else GlassTint.tint(sample, adaptive, level)
    }
    val appliedFallback: Int get() = GlassTint.scale(fixedFallback, level)

    /** What the last draw actually painted, and over what (for tests). */
    var drawnColor = 0
        private set
    var drewBlur = false
        private set

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderColor = border
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = border
        strokeWidth = borderWidth
    }
    private val rect = RectF()
    private val clip = Path()
    private val here = IntArray(2)
    private val screen = IntArray(2)

    init {
        synchronized(hosts) { hosts[host] = true }
    }

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val r = minOf(radius, rect.height() / 2, rect.width() / 2)
        val root = host.rootView
        val blur = if (wallpaper) WallpaperBlur.get(host.context, root.width, root.height) { host.invalidate() } else null
        if (blur == null) {
            drewBlur = false
            paintTint(appliedFallback)
            canvas.drawRoundRect(rect, r, r, tintPaint)
            drawBorder(canvas, r)
            return
        }
        // Line the blurred wallpaper up with the screen: this view's corner is at (here - screen).
        host.getLocationOnScreen(here)
        root.getLocationOnScreen(screen)
        clip.reset()
        clip.addRoundRect(rect, r, r, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.translate((screen[0] - here[0]).toFloat(), (screen[1] - here[1]).toFloat())
        val scale = root.width / blur.width.toFloat()
        canvas.scale(scale, root.height / blur.height.toFloat())
        blurPaint.alpha = alpha
        canvas.drawBitmap(blur, 0f, 0f, blurPaint)
        canvas.restore()
        drewBlur = true
        paintTint(appliedTint)
        canvas.drawRoundRect(rect, r, r, tintPaint)
        drawBorder(canvas, r)
    }

    /** The tint as the setting scales it, with this drawable's own alpha on top. */
    private fun paintTint(color: Int) {
        drawnColor = color
        tintPaint.color = color
        tintPaint.alpha = (color ushr 24) * alpha / 255
    }

    /** Inside the edge, so the outline (and clipping) still match the shape. */
    private fun drawBorder(canvas: Canvas, r: Float) {
        val w = borderPaint.strokeWidth
        if (w <= 0f || borderColor == 0) return
        borderPaint.alpha = (borderColor ushr 24) * alpha / 255
        rect.inset(w / 2, w / 2)
        canvas.drawRoundRect(rect, r - w / 2, r - w / 2, borderPaint)
        rect.inset(-w / 2, -w / 2)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, minOf(radius, bounds.height() / 2f, bounds.width() / 2f))
    }

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
        invalidateSelf()
    }

    override fun getAlpha() = alpha

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    companion object {
        /** The "Glass transparency" setting, 0 (Clear) to 100 (Solid); see [setLevel]. */
        @Volatile var level = GlassTint.LEVEL_DEFAULT
            private set

        /** Every view with glass behind it (weakly: gone views drop out). */
        private val hosts = WeakHashMap<View, Boolean>()
        private val listeners = mutableListOf<(Int) -> Unit>()

        /**
         * The setting's one way in (the slider, and each screen's start from Prefs): every live
         * glass surface redraws with it at once, and listeners (text shadows) hear of it.
         */
        fun setLevel(value: Int) {
            val l = value.coerceIn(0, GlassTint.LEVEL_MAX)
            val changed = l != level
            level = l
            val views = synchronized(hosts) { hosts.keys.toList() }
            val onMain = Looper.myLooper() == Looper.getMainLooper()
            for (v in views) if (onMain) v.invalidate() else v.postInvalidate()
            if (changed) synchronized(listeners) { listeners.toList() }.forEach { it(l) }
        }

        fun addListener(l: (Int) -> Unit) = synchronized(listeners) { listeners += l }
        fun removeListener(l: (Int) -> Unit) = synchronized(listeners) { listeners -= l }
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
