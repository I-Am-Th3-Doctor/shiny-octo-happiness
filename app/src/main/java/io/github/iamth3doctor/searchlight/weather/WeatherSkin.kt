package io.github.iamth3doctor.searchlight.weather

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The weather's look: one skin, Frost (no switching). The chrome (text, pills, panels, the scrub
 * bar's pin, the big number, the Weather widgets' cards) is monochrome frosted glass: dark text on
 * translucent white. The weather itself, in [scene], keeps its natural colors and looks real: blue
 * sky, yellow sunlight, white clouds, blue rain.
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
     * Frost: the only look. Monochrome frosted glass chrome (dark text on translucent white panels,
     * like the launcher's frosted home card) over the weather in its natural colors.
     */
    FROST(
        "frost", "Frost", light = true,
        ink = 0xFF1C1C21.toInt(), inkDim = 0xB31C1C21.toInt(),
        accent = 0xFF2A2A30.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentDeep = 0xFF8A8E96.toInt(),
        numberTop = 0xFF2A2A30.toInt(), numberBottom = 0xFF111114.toInt(),
        calloutBg = 0xC8FFFFFF.toInt(), outline = 0xE6FFFFFF.toInt(), glass = 0xB8FFFFFF.toInt(),
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
         * The weather as it looks: a deep blue night sky, blue by day, warm low on the horizon at
         * dawn and dusk; yellow sunlight, a pale moon, white clouds graying under rain, blue rain,
         * white snow, dark hills.
         */
        val DEFAULT = ScenePalette(
            skyTop = intArrayOf(0xFF050A1A.toInt(), 0xFF0C1736.toInt(), 0xFF22325E.toInt(), 0xFF3E62A0.toInt(), 0xFF3F7FCB.toInt(), 0xFF2F7AD8.toInt()),
            skyBottom = intArrayOf(0xFF111C38.toInt(), 0xFF26305A.toInt(), 0xFFB86F6A.toInt(), 0xFFF2A46E.toInt(), 0xFFB9D5EE.toInt(), 0xFF9FCBF2.toInt()),
            grayDay = 0xFF7E8894.toInt() to 0xFFB8C0C9.toInt(), grayNight = 0xFF12161D.toInt() to 0xFF232830.toInt(),
            sunCore = 0xFFFFF8DC.toInt() to 0xFFFFC870.toInt(), sunGlow = 0xFFFFE680.toInt() to 0xFFFFA64D.toInt(),
            moon = 0xFFF2F0E6.toInt(), moonDark = 0xFF2A3045.toInt(), moonGlow = 0xFFDDE5F5.toInt(),
            cloudClear = 0xFFFDFDFE.toInt(), cloudRain = 0xFF6E7378.toInt(), cloudNight = 0xFF2B313D.toInt(), cloudDusk = 0xFFF4B48C.toInt(),
            rain = 0xFF7FAEE0.toInt(), snow = 0xFFFFFFFF.toInt(), fog = 0xFFDADEE3.toInt() to 0xFF3A4049.toInt(),
            hillFar = 0xFF101620.toInt(), hillNear = 0xFF090C12.toInt(), stars = 0xFFFFFFFF.toInt(), birds = 0xFF2A2F3A.toInt() to 0xFF101318.toInt(),
        )
    }
}
