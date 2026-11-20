package com.something.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import kotlin.math.abs

/**
 * Turns any app icon into a white dot-matrix glyph on a [GRID]×[GRID] grid.
 *
 * Source, best first:
 *  1. the adaptive icon's monochrome layer (Android 13+): made for exactly this;
 *  2. the adaptive foreground layer: usually a logo on transparency;
 *  3. the whole icon.
 * If the source is mostly opaque (a logo on a solid plate), lit dots follow luminance contrast
 * against the plate, plus the plate's outline, so the logo still reads.
 */
object DotIcons {
    const val GRID = 18

    fun render(icon: Drawable, px: Int): Bitmap {
        val (src, adaptive, monochrome) = pickSource(icon)
        val values = sample(src, adaptive, monochrome)
        return draw(values, px)
    }

    private fun pickSource(icon: Drawable): Triple<Drawable, Boolean, Boolean> {
        if (icon is AdaptiveIconDrawable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                icon.monochrome?.let { return Triple(it, true, true) }
            }
            icon.foreground?.let { return Triple(it, true, false) }
        }
        return Triple(icon, false, false)
    }

    /** Returns GRID*GRID intensities in 0..1. */
    fun sample(src: Drawable, adaptive: Boolean, monochrome: Boolean): FloatArray {
        val s = 108
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val oldBounds = src.copyBounds()
        src.setBounds(0, 0, s, s)
        src.draw(canvas)
        src.bounds = oldBounds
        // Adaptive layers are 108 units with the visible area in the middle 72.
        val crop = if (adaptive) 18 else 0
        val span = s - 2 * crop
        val pixels = IntArray(s * s)
        bmp.getPixels(pixels, 0, s, 0, 0, s, s)
        bmp.recycle()

        val g = GRID
        val alpha = FloatArray(g * g)
        val lum = FloatArray(g * g)
        for (cy in 0 until g) for (cx in 0 until g) {
            val x0 = crop + cx * span / g
            val x1 = crop + (cx + 1) * span / g
            val y0 = crop + cy * span / g
            val y1 = crop + (cy + 1) * span / g
            var a = 0f; var l = 0f; var n = 0
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = pixels[y * s + x]
                val pa = Color.alpha(p) / 255f
                a += pa
                l += pa * (0.299f * Color.red(p) + 0.587f * Color.green(p) + 0.114f * Color.blue(p)) / 255f
                n++
            }
            val i = cy * g + cx
            alpha[i] = if (n == 0) 0f else a / n
            lum[i] = if (a <= 0f) 0f else l / a
        }
        if (monochrome) return alpha

        val opaque = alpha.count { it > 0.6f }.toFloat() / alpha.size
        if (opaque < 0.5f) return alpha // a logo on transparency: its silhouette is the glyph

        // Logo on a plate: contrast against the plate's typical luminance, plus the plate's edge.
        val plate = lum.filterIndexed { i, _ -> alpha[i] > 0.6f }.sorted().let { it[it.size / 2] }
        val out = FloatArray(g * g)
        for (cy in 0 until g) for (cx in 0 until g) {
            val i = cy * g + cx
            if (alpha[i] < 0.4f) continue
            var v = (abs(lum[i] - plate) * 3f).coerceIn(0f, 1f)
            val edge = listOf(cx - 1 to cy, cx + 1 to cy, cx to cy - 1, cx to cy + 1).any { (x, y) ->
                x !in 0 until g || y !in 0 until g || alpha[y * g + x] < 0.4f
            }
            if (edge) v = maxOf(v, 0.55f)
            out[i] = v
        }
        return out
    }

    fun draw(values: FloatArray, px: Int): Bitmap {
        val g = GRID
        val out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val cell = px.toFloat() / g
        for (y in 0 until g) for (x in 0 until g) {
            val v = values[y * g + x]
            val r = when {
                v >= 0.45f -> cell * 0.42f
                v >= 0.2f -> cell * 0.26f
                else -> continue
            }
            c.drawCircle((x + 0.5f) * cell, (y + 0.5f) * cell, r, paint)
        }
        return out
    }
}
