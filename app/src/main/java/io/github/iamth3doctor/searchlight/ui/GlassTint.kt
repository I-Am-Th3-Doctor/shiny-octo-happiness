package io.github.iamth3doctor.searchlight.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The home card's glass takes its color from the wallpaper behind it: [sample] finds the
 * dominant color of that strip, [tint] turns it into a dark, legible tint of the same hue.
 * Pure; JVM-tested.
 */
object GlassTint {

    /**
     * The card and the search row on it, one step lighter. At the Clear end of the glass
     * setting the glass may get as light as [clearMaxLuminance], no lighter: white text keeps
     * about 3:1 on the card (still readable) over any wallpaper.
     */
    enum class Layer(val lightness: Float, val maxLuminance: Float, val clearMaxLuminance: Float) {
        CARD(lightness = 0.24f, maxLuminance = 0.15f, clearMaxLuminance = 0.30f),
        ROW(lightness = 0.32f, maxLuminance = 0.20f, clearMaxLuminance = 0.36f),
    }

    /**
     * Average color of [pixels], weighted toward saturated ones, so a little color on a grey
     * strip still sets the hue (a plain average turns muddy). Opaque ARGB out; null if empty.
     */
    fun sample(pixels: IntArray): Int? {
        if (pixels.isEmpty()) return null
        var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
        for (p in pixels) {
            val pr = (p shr 16) and 0xFF; val pg = (p shr 8) and 0xFF; val pb = p and 0xFF
            val w = 0.05 + saturation(pr, pg, pb)
            r += pr * w; g += pg * w; b += pb * w; total += w
        }
        return argb(0xFF, (r / total).toInt(), (g / total).toInt(), (b / total).toInt())
    }

    /**
     * The sampled hue at [Layer.lightness] (HSL), with saturation kept (raised to a minimum
     * for clearly colored wallpapers, so the glass visibly takes their color). Its alpha is
     * just high enough that, over the blurred wallpaper (≈ [sample]), the glass stays below
     * [Layer.maxLuminance]: white text on it keeps solid contrast.
     */
    fun tint(sample: Int, layer: Layer, level: Int = LEVEL_DEFAULT): Int {
        val (h, s, _) = hsl(sample)
        val sat = when {
            s < NEUTRAL -> s // grey wallpaper: grey glass
            else -> s.coerceIn(MIN_SATURATION, MAX_SATURATION)
        }
        val rgb = fromHsl(h, sat, layer.lightness)
        val behind = luminance(sample)
        val glass = luminance(rgb)
        val stock = if (behind <= layer.maxLuminance) MIN_ALPHA
            else ((behind - layer.maxLuminance) / (behind - glass)).coerceIn(MIN_ALPHA, MAX_ALPHA)
        var alpha = scaleAlpha(stock, level)
        if (level < LEVEL_DEFAULT && behind > layer.clearMaxLuminance) {
            // Clearer than stock, but never so clear that white text on it can't be read.
            val legible = (behind - layer.clearMaxLuminance) / (behind - glass)
            alpha = max(alpha, min(legible, stock))
        }
        return (rgb and 0xFFFFFF) or ((alpha * 255).toInt() shl 24)
    }

    // ---- The "Glass transparency" setting: 0 (Clear) to 100 (Solid), the stock look at 50 ----

    /**
     * A surface's stock tint opacity [alpha] at [level]. The light the glass lets through is
     * raised to a power, 1/4 at Clear to 4 at Solid (1 at the default: exactly the stock
     * alpha). One curve for every surface, rising with the stock alpha, so a lighter surface
     * stays lighter at every level. Clear keeps [floor] of the stock opacity; Solid stops at
     * [SOLID_MAX], a hint of glass left.
     */
    fun scaleAlpha(alpha: Float, level: Int, floor: Float = FLOOR_BLURRED): Float {
        if (level == LEVEL_DEFAULT) return alpha
        val t = (level.coerceIn(0, LEVEL_MAX) - LEVEL_DEFAULT) / LEVEL_DEFAULT.toFloat() // -1..1
        val through = (1 - alpha).pow(SOLID_POWER.pow(t))
        return max(1 - through, alpha * floor).coerceAtMost(max(alpha, SOLID_MAX))
    }

    /** [color] with its alpha scaled by [scaleAlpha]. */
    fun scale(color: Int, level: Int, floor: Float = FLOOR_BLURRED): Int {
        if (level == LEVEL_DEFAULT) return color
        val alpha = scaleAlpha((color ushr 24) / 255f, level, floor)
        return (color and 0xFFFFFF) or ((alpha * 255 + 0.5f).toInt() shl 24)
    }

    const val LEVEL_DEFAULT = 50
    const val LEVEL_MAX = 100
    /** Clear-end floors: over the blur, and over an unblurred wallpaper (needs more). */
    const val FLOOR_BLURRED = 0.5f
    const val FLOOR_PLAIN = 0.8f

    /** WCAG relative luminance, 0 (black) to 1 (white). */
    fun luminance(color: Int): Float {
        fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return (0.2126 * lin((color shr 16) and 0xFF) + 0.7152 * lin((color shr 8) and 0xFF) + 0.0722 * lin(color and 0xFF)).toFloat()
    }

    /** Hue (0–360), saturation and lightness (0–1). */
    fun hsl(color: Int): Triple<Float, Float, Float> {
        val r = ((color shr 16) and 0xFF) / 255f; val g = ((color shr 8) and 0xFF) / 255f; val b = (color and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b)); val d = mx - mn
        val l = (mx + mn) / 2
        if (d == 0f) return Triple(0f, 0f, l)
        val s = d / (1 - abs(2 * l - 1))
        val h = when (mx) {
            r -> 60 * (((g - b) / d) % 6)
            g -> 60 * ((b - r) / d + 2)
            else -> 60 * ((r - g) / d + 4)
        }
        return Triple((h + 360) % 360, s, l)
    }

    fun fromHsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - abs(2 * l - 1)) * s
        val x = c * (1 - abs((h / 60) % 2 - 1))
        val m = l - c / 2
        val (r, g, b) = when ((h / 60).toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        return argb(0xFF, ((r + m) * 255).toInt(), ((g + m) * 255).toInt(), ((b + m) * 255).toInt())
    }

    private fun saturation(r: Int, g: Int, b: Int): Double {
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        return if (mx == 0) 0.0 else (mx - mn) / mx.toDouble()
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    private const val NEUTRAL = 0.08f
    private const val MIN_SATURATION = 0.35f
    private const val MAX_SATURATION = 0.85f
    private const val MIN_ALPHA = 0.5f
    private const val MAX_ALPHA = 0.92f
    private const val SOLID_POWER = 4f
    private const val SOLID_MAX = 0.95f
}
