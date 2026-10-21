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
 * corners. Without a readable wallpaper (see [WallpaperBlur]) it's the [fallbackTint] alone,
 * which is darker so text stays readable on an unblurred wallpaper.
 */
class FrostedDrawable(
    private val host: View,
    private val radius: Float,
    @ColorInt tint: Int,
    @ColorInt fallbackTint: Int,
) : Drawable() {

    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fallbackTint }
    private val blurPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val here = IntArray(2)
    private val screen = IntArray(2)

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        val r = minOf(radius, rect.height() / 2, rect.width() / 2)
        val root = host.rootView
        val blur = WallpaperBlur.get(host.context, root.width, root.height) { host.invalidate() }
        if (blur == null) {
            canvas.drawRoundRect(rect, r, r, fallbackPaint)
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
        canvas.drawBitmap(blur, 0f, 0f, blurPaint)
        canvas.restore()
        canvas.drawRoundRect(rect, r, r, tintPaint)
    }

    override fun getOutline(outline: Outline) {
        outline.setRoundRect(bounds, minOf(radius, bounds.height() / 2f, bounds.width() / 2f))
    }

    override fun setAlpha(alpha: Int) {
        tintPaint.alpha = alpha
        blurPaint.alpha = alpha
        fallbackPaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
