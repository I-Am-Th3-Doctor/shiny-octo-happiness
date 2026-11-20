package io.github.iamth3doctor.searchlight.weather

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The weather's look: one skin, Frost (monochrome frosted glass; no color, no switching). It sets
 * the scene (sky, sun, moon, clouds, rain, hills, the cartoon outlines), the text over it, the
 * marks (the scrub bar's pin, pills, the big number) and the Weather widgets' cards, all in grays:
 * dark text over a pale scene.
 *
 * Pure colors (ARGB ints); JVM-tested for contrast.
 */
enum class WeatherSkin(
    val key: String,
    /** Its name in the menu. */
    val title: String,
    /** A light skin: dark text over a pale scene. */
    val light: Boolean,
    /** Text over the scene, and its muted form. */
    val ink: Int,
    val inkDim: Int,
    /** The accent (the scrub bar's NOW pin, pills, the timeline's NOW), and text over it. */
    val accent: Int,
    val onAccent: Int,
    /** A second accent: the big number's extrusion, the leader lines' dots. */
    val accentDeep: Int,
    /** The big number's face (top to bottom). */
    val numberTop: Int,
    val numberBottom: Int,
    /** Behind a callout's text, so it reads over any sky. */
    val calloutBg: Int,
    /** The cartoon outlines round the sun, moon and clouds. */
    val outline: Int,
    /** The buttons' and pills' glass. */
    val glass: Int,
    /** The widgets' cards (top, bottom), and the 3-day widget's (it's light on the default skin). */
    val cardTop: Int,
    val cardBottom: Int,
    val daysCardTop: Int,
    val daysCardBottom: Int,
    val scene: ScenePalette,
) {
    /**
     * Frost: the only look. Monochrome frosted glass, no color: pale gray skies, white clouds,
     * dark text on translucent white panels, like the launcher's frosted home card.
     */
    FROST(
        "frost", "Frost", light = true,
        ink = 0xFF1C1C21.toInt(), inkDim = 0xB31C1C21.toInt(),
        accent = 0xFF2A2A30.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentDeep = 0xFF8A8E96.toInt(),
        numberTop = 0xFF2A2A30.toInt(), numberBottom = 0xFF111114.toInt(),
        calloutBg = 0xC8FFFFFF.toInt(), outline = 0xFF1C1C21.toInt(), glass = 0xB8FFFFFF.toInt(),
        cardTop = 0xD9FFFFFF.toInt(), cardBottom = 0xCCF0F1F3.toInt(),
        daysCardTop = 0xD9FFFFFF.toInt(), daysCardBottom = 0xCCF0F1F3.toInt(),
        scene = ScenePalette.DEFAULT,
    );

    /** A widget card with dark text: the 3-day one on the default skin, and every card on a light skin. */
    fun cardLight(threeDays: Boolean): Boolean = light
    fun cardInk(threeDays: Boolean): Int = if (cardLight(threeDays)) 0xFF1C1C21.toInt() else ink
    fun cardMuted(threeDays: Boolean): Int = if (cardLight(threeDays)) 0x991C1C21.toInt() else inkDim
    fun cardColors(threeDays: Boolean): Pair<Int, Int> = if (threeDays) daysCardTop to daysCardBottom else cardTop to cardBottom

    companion object {
        val DEFAULT = FROST
        fun of(key: String?): WeatherSkin = entries.firstOrNull { it.key == key } ?: DEFAULT

        /** WCAG relative luminance of an opaque color. */
        fun luminance(c: Int): Double {
            fun ch(s: Int): Double {
                val v = ((c shr s) and 0xFF) / 255.0
                return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
            }
            return 0.2126 * ch(16) + 0.7152 * ch(8) + 0.0722 * ch(0)
        }

        /** WCAG contrast ratio between two opaque colors (1..21). */
        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
        }

        /** [top] (with its alpha) over the opaque [under]. */
        fun over(top: Int, under: Int): Int {
            val a = ((top ushr 24) and 0xFF) / 255.0
            fun ch(s: Int) = ((((top shr s) and 0xFF) * a + ((under shr s) and 0xFF) * (1 - a)).toInt().coerceIn(0, 255)) shl s
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}

/**
 * A skin's scene colors. Sky stops run by the sun's altitude, night to full day (-18, -10, -4, 0,
 * 6, 15 degrees); a pair is (day, night) or (clear, low sun).
 */
class ScenePalette(
    val skyTop: IntArray,
    val skyBottom: IntArray,
    /** Overcast sky: (top, bottom) by day and by night. */
    val grayDay: Pair<Int, Int>,
    val grayNight: Pair<Int, Int>,
    /** The sun: high, then low. */
    val sunCore: Pair<Int, Int>,
    val sunGlow: Pair<Int, Int>,
    val moon: Int,
    val moonDark: Int,
    val moonGlow: Int,
    val cloudClear: Int,
    val cloudRain: Int,
    val cloudNight: Int,
    val cloudDusk: Int,
    val rain: Int,
    val snow: Int,
    /** Fog: by day, by night. */
    val fog: Pair<Int, Int>,
    val hillFar: Int,
    val hillNear: Int,
    val stars: Int,
    /** Birds: by day, by night. */
    val birds: Pair<Int, Int>,
    /** How much of the sky's own color the hills take on (the default's are tinted by it). */
    val hillMix: Double = 0.45,
) {
    companion object {
        /**
         * Frosted monochrome: pale gray skies (a little darker at night), white clouds over grays,
         * a soft gray sun and a white moon, gray rain and hills. No color.
         */
        val DEFAULT = ScenePalette(
            skyTop = intArrayOf(0xFFB9BCC2.toInt(), 0xFFC6C9CE.toInt(), 0xFFD6D8DC.toInt(), 0xFFE2E4E7.toInt(), 0xFFECEDEF.toInt(), 0xFFE6E8EB.toInt()),
            skyBottom = intArrayOf(0xFFCDD0D4.toInt(), 0xFFD6D8DB.toInt(), 0xFFE4E5E7.toInt(), 0xFFEEEFF0.toInt(), 0xFFF5F5F6.toInt(), 0xFFF2F3F4.toInt()),
            grayDay = 0xFFCFD2D6.toInt() to 0xFFE3E5E8.toInt(), grayNight = 0xFFB4B7BC.toInt() to 0xFFC8CBCF.toInt(),
            sunCore = 0xFFFFFFFF.toInt() to 0xFFF4F4F4.toInt(), sunGlow = 0xFFFFFFFF.toInt() to 0xFFEDEDED.toInt(),
            moon = 0xFFFFFFFF.toInt(), moonDark = 0xFFA9ADB3.toInt(), moonGlow = 0xFFFFFFFF.toInt(),
            cloudClear = 0xFFFFFFFF.toInt(), cloudRain = 0xFFA4A8AE.toInt(), cloudNight = 0xFFD8DADD.toInt(), cloudDusk = 0xFFF2F2F2.toInt(),
            rain = 0xFF5C5F64.toInt(), snow = 0xFF7E8186.toInt(), fog = 0xFFFFFFFF.toInt() to 0xFFE9EAEC.toInt(),
            hillFar = 0xFFC3C6CB.toInt(), hillNear = 0xFFA9ADB3.toInt(), stars = 0xFF7E8186.toInt(), birds = 0xFF2A2A30.toInt() to 0xFF2A2A30.toInt(),
            hillMix = 0.0,
        )
    }
}
