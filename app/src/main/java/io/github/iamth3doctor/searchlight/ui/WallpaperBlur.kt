package io.github.iamth3doctor.searchlight.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A blurred, screen-shaped copy of the wallpaper, for frosted panels to draw behind themselves.
 *
 * Built off the main thread at 1/[DOWNSCALE] size (scaled back up when drawn, which smooths it
 * further) and cached until the wallpaper or screen size changes. Unavailable (null) for live
 * wallpapers, and when the wallpaper can't be read: Android only lets apps with All Files Access
 * read it. Panels then fall back to their plain tint.
 */
object WallpaperBlur {
    private const val DOWNSCALE = 8
    private const val RADIUS = 6 // in downscaled pixels

    private val main = Handler(Looper.getMainLooper())
    private var cached: Bitmap? = null
    private var cachedFor = 0L
    private var building = 0L
    private var generation = 0
    private val waiting = ArrayList<() -> Unit>()

    /** The blur for a [width]×[height] screen, or null (then [onReady] runs once it's built). */
    fun get(context: Context, width: Int, height: Int, onReady: () -> Unit): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val key = (width.toLong() shl 32) or height.toLong()
        if (cachedFor == key) return cached
        waiting += onReady
        if (building != key) {
            building = key
            val gen = generation
            val app = context.applicationContext
            Thread {
                val bmp = runCatching { build(app, width, height) }.getOrNull()
                main.post {
                    if (gen != generation) return@post
                    cached = bmp
                    cachedFor = key
                    building = 0L
                    val callbacks = waiting.toList()
                    waiting.clear()
                    callbacks.forEach { it() }
                }
            }.start()
        }
        return null
    }

    /** The wallpaper changed: rebuild on next use. */
    fun invalidate() {
        generation++
        cached = null
        cachedFor = 0L
        building = 0L
    }

    private fun build(context: Context, width: Int, height: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) return null
        val wm = WallpaperManager.getInstance(context)
        if (wm.wallpaperInfo != null) return null // live wallpaper: no still image to match
        val drawable = try { wm.drawable } catch (e: SecurityException) { null } ?: return null
        val dw = drawable.intrinsicWidth
        val dh = drawable.intrinsicHeight
        if (dw <= 0 || dh <= 0) return null

        // Like the system draws it: scaled to cover the screen, centered.
        val w = max(1, width / DOWNSCALE)
        val h = max(1, height / DOWNSCALE)
        val scale = max(w / dw.toFloat(), h / dh.toFloat())
        val sw = (dw * scale).roundToInt()
        val sh = (dh * scale).roundToInt()
        val left = (w - sw) / 2
        val top = (h - sh) / 2
        val small = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawable.setBounds(left, top, left + sw, top + sh)
        drawable.draw(Canvas(small))

        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        BoxBlur.blur(px, w, h, RADIUS)
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small
    }
}
