package io.github.iamth3doctor.searchlight.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.View
import androidx.annotation.ColorInt

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
 * Every tint is scaled by the "Glass transparency" setting ([level], see [GlassTint.scaleAlpha]),
 * read when drawing: changing it and invalidating ([invalidateAll]) is enough.
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
    private var tintFrom: Int? = null
    private var tintLevel = GlassTint.LEVEL_DEFAULT
    private var fallbackLevel = GlassTint.LEVEL_DEFAULT

    /** The tints as last set up, and whether the last draw was over the blur (for tests). */
    val appliedTint get() = tintPaint.color
    val appliedFallback get() = fallbackPaint.color
    var drewBlur = false
        private set

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fallbackTint }
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = border
        strokeWidth = borderWidth
    }
    private val rect = RectF()
    private val clip = Path()
    private val here = IntArray(2)
    private val screen = IntArray(2)

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val r = minOf(radius, rect.height() / 2, rect.width() / 2)
        val root = host.rootView
        val blur = if (wallpaper) WallpaperBlur.get(host.context, root.width, root.height) { host.invalidate() } else null
        if (blur == null) {
            if (fallbackLevel != level) {
                fallbackLevel = level
                fallbackPaint.color = GlassTint.scale(fixedFallback, level, GlassTint.FLOOR_PLAIN)
            }
            drewBlur = false
            canvas.drawRoundRect(rect, r, r, fallbackPaint)
            drawBorder(canvas, r)
            return
        }
        val sample = if (adaptive != null) WallpaperBlur.sampledColor() else null
        if (sample != tintFrom || tintLevel != level) {
            tintFrom = sample
            tintLevel = level
            tintPaint.color = if (sample == null || adaptive == null) GlassTint.scale(fixedTint, level)
                else GlassTint.tint(sample, adaptive, level)
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
        canvas.drawBitmap(blur, 0f, 0f, blurPaint)
        canvas.restore()
        drewBlur = true
        canvas.drawRoundRect(rect, r, r, tintPaint)
        drawBorder(canvas, r)
    }

    /** Inside the edge, so the outline (and clipping) still match the shape. */
    private fun drawBorder(canvas: Canvas, r: Float) {
        val w = borderPaint.strokeWidth
        if (w <= 0f || borderPaint.color == 0) return
        rect.inset(w / 2, w / 2)
        canvas.drawRoundRect(rect, r - w / 2, r - w / 2, borderPaint)
        rect.inset(-w / 2, -w / 2)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, minOf(radius, bounds.height() / 2f, bounds.width() / 2f))
    }

    override fun setAlpha(alpha: Int) {
        tintPaint.alpha = alpha
        blurPaint.alpha = alpha
        borderPaint.alpha = alpha
        fallbackPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    companion object {
        /** The "Glass transparency" setting, 0 (Clear) to 100 (Solid); set from Prefs at start. */
        @Volatile var level = GlassTint.LEVEL_DEFAULT

        /** Redraws every frosted surface under [root] (after [level] changed). */
        fun invalidateAll(root: View) {
            if (root.background is FrostedDrawable) root.invalidate()
            if (root is android.view.ViewGroup) for (i in 0 until root.childCount) invalidateAll(root.getChildAt(i))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
