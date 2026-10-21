package io.github.iamth3doctor.searchlight.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import io.github.iamth3doctor.searchlight.R
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A blurred, screen-shaped copy of the wallpaper, for frosted panels to draw behind themselves.
 *
 * Built off the main thread at 1/[DOWNSCALE] size (scaled back up when drawn, which smooths it
 * further) and cached until the wallpaper or screen size changes. Unavailable (null) for live
 * wallpapers, and when the wallpaper can't be read: on Android 13+ that takes "Photos & videos"
 * access (READ_MEDIA_IMAGES; Settings › Permissions, photo search). Panels then fall back to
 * their plain tint.
 *
 * Alongside the blur it samples the color of the wallpaper's bottom strip (where the home card
 * sits) for the card's tinted glass ([sampledColor], see [GlassTint]); cached the same way.
 */
object WallpaperBlur {
    private const val DOWNSCALE = 8
    /** Bottom share of the screen sampled for the card's color: the card and a little above. */
    private const val CARD_STRIP = 0.22f

    private val main = Handler(Looper.getMainLooper())
    private var cached: Bitmap? = null
    private var cachedColor: Int? = null
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
                val built = runCatching { build(app, width, height) }.getOrNull()
                main.post {
                    if (gen != generation) return@post
                    cached = built?.first
                    cachedColor = built?.second
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

    /** The wallpaper's color behind the home card, once [get] has built it; null if it couldn't be read. */
    fun sampledColor(): Int? = cachedColor

    /** Try again if the last attempt couldn't read the wallpaper (access may have been granted since). */
    fun retryIfUnavailable() {
        if (cachedFor != 0L && cached == null) invalidate()
    }

    /** The wallpaper changed: rebuild on next use. */
    fun invalidate() {
        generation++
        cached = null
        cachedColor = null
        cachedFor = 0L
        building = 0L
    }

    /**
     * The blur_radius token (24dp) as a box radius on the downscaled image. Like Android's own
     * blur radius, it maps to a Gaussian sigma of ~0.58 × radius; three box passes of radius r
     * have a sigma of about r.
     */
    private fun boxRadius(context: Context): Int {
        val sigmaPx = context.resources.getDimension(R.dimen.blur_radius) * 0.57735f + 0.5f
        return max(1, (sigmaPx / DOWNSCALE).roundToInt())
    }

    private fun build(context: Context, width: Int, height: Int): Pair<Bitmap, Int?>? {
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
        // The strip behind the home card, before blurring (blurring would grey the hue).
        val stripTop = (h * (1 - CARD_STRIP)).toInt()
        val color = GlassTint.sample(px.copyOfRange(stripTop * w, px.size))
        BoxBlur.blur(px, w, h, boxRadius(context))
        small.setPixels(px, 0, w, 0, 0, w, h)
        return small to color
    }
}
