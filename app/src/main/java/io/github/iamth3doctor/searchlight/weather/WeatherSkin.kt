package io.github.iamth3doctor.searchlight.weather

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The weather app's skins: whole-app color themes, switched in one tap from the weather screen's
 * menu. Each one colors the scene (sky, sun, moon, clouds, rain, hills, the cartoon outlines), the
 * text over it, the accents (the scrub bar's pin, pills, the big number) and the Weather widgets.
 * High contrast throughout: light text over the dark skins' scenes, dark text over the light one.
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
    GOLD(
        "gold", "Gold", light = false,
        ink = 0xFFFFFFFF.toInt(), inkDim = 0xCCFFFFFF.toInt(),
        accent = 0xFFFFC93C.toInt(), onAccent = 0xFF1A1408.toInt(), accentDeep = 0xFF9C4A2F.toInt(),
        numberTop = 0xFFFFE7A3.toInt(), numberBottom = 0xFFF0A23A.toInt(),
        calloutBg = 0xB3101016.toInt(), outline = 0xFF1A1408.toInt(), glass = 0x990B0B0D.toInt(),
        cardTop = 0xFF1C1C21.toInt(), cardBottom = 0xFF0E0E11.toInt(),
        daysCardTop = 0xFFFBF6EC.toInt(), daysCardBottom = 0xFFF1E7D6.toInt(),
        scene = ScenePalette.DEFAULT,
    ),
    EMBER(
        "ember", "Ember", light = false,
        ink = 0xFFFFFFFF.toInt(), inkDim = 0xCCFFFFFF.toInt(),
        accent = 0xFFFF7A1A.toInt(), onAccent = 0xFF140A04.toInt(), accentDeep = 0xFF8A2E06.toInt(),
        numberTop = 0xFFFFFFFF.toInt(), numberBottom = 0xFFFFD9BF.toInt(),
        calloutBg = 0x99000000.toInt(), outline = 0xFF000000.toInt(), glass = 0xB3141416.toInt(),
        cardTop = 0xFF17171A.toInt(), cardBottom = 0xFF08080A.toInt(),
        daysCardTop = 0xFF17171A.toInt(), daysCardBottom = 0xFF08080A.toInt(),
        scene = ScenePalette(
            skyTop = intArrayOf(0xFF050506.toInt(), 0xFF09090B.toInt(), 0xFF111013.toInt(), 0xFF17151A.toInt(), 0xFF1C1A20.toInt(), 0xFF202027.toInt()),
            skyBottom = intArrayOf(0xFF0E0B0A.toInt(), 0xFF181210.toInt(), 0xFF2A1810.toInt(), 0xFF3A2014.toInt(), 0xFF2E2420.toInt(), 0xFF2A2A30.toInt()),
            grayDay = 0xFF2A2A30.toInt() to 0xFF34343B.toInt(), grayNight = 0xFF0C0C0E.toInt() to 0xFF141417.toInt(),
            sunCore = 0xFFFF7A1A.toInt() to 0xFFFF5A0A.toInt(), sunGlow = 0xFFFF9A3D.toInt() to 0xFFFF6A1A.toInt(),
            moon = 0xFFF2F2F2.toInt(), moonDark = 0xFF2A2A30.toInt(), moonGlow = 0xFFFFE2C8.toInt(),
            cloudClear = 0xFF4A4A52.toInt(), cloudRain = 0xFF2C2C33.toInt(), cloudNight = 0xFF1E1E24.toInt(), cloudDusk = 0xFFFF8A3D.toInt(),
            rain = 0xFFFFB27A.toInt(), snow = 0xFFFFFFFF.toInt(), fog = 0xFF3A3A40.toInt() to 0xFF1A1A1E.toInt(),
            hillFar = 0xFF0A0A0C.toInt(), hillNear = 0xFF000000.toInt(), stars = 0xFFFFFFFF.toInt(), birds = 0xFF000000.toInt() to 0xFF000000.toInt(),
        ),
    ),
    PAPER(
        "paper", "Paper", light = true,
        ink = 0xFF1C1C21.toInt(), inkDim = 0xB31C1C21.toInt(),
        accent = 0xFF9C4A2F.toInt(), onAccent = 0xFFFFFFFF.toInt(), accentDeep = 0xFFFFC93C.toInt(),
        numberTop = 0xFF2A2A30.toInt(), numberBottom = 0xFF111114.toInt(),
        calloutBg = 0xD9FFFFFF.toInt(), outline = 0xFF1C1C21.toInt(), glass = 0xE6FFFFFF.toInt(),
        cardTop = 0xFFFFFFFF.toInt(), cardBottom = 0xFFF3F3F1.toInt(),
        daysCardTop = 0xFFFFFFFF.toInt(), daysCardBottom = 0xFFF3F3F1.toInt(),
        scene = ScenePalette(
            skyTop = intArrayOf(0xFFE4E7EE.toInt(), 0xFFEAECF2.toInt(), 0xFFF2F2F6.toInt(), 0xFFFAF8F6.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()),
            skyBottom = intArrayOf(0xFFEDEFF4.toInt(), 0xFFF2F1F4.toInt(), 0xFFFAEDE6.toInt(), 0xFFFCF0E4.toInt(), 0xFFFBF7F0.toInt(), 0xFFF4F6F8.toInt()),
            grayDay = 0xFFE9EBEE.toInt() to 0xFFF4F5F6.toInt(), grayNight = 0xFFDFE2E7.toInt() to 0xFFEBEDF0.toInt(),
            sunCore = 0xFFFFC93C.toInt() to 0xFFFF9E57.toInt(), sunGlow = 0xFFFFE9A8.toInt() to 0xFFFFC9A0.toInt(),
            moon = 0xFFFFFFFF.toInt(), moonDark = 0xFFD5D8DE.toInt(), moonGlow = 0xFFFFFFFF.toInt(),
            cloudClear = 0xFFFFFFFF.toInt(), cloudRain = 0xFFC9CED6.toInt(), cloudNight = 0xFFE3E6EC.toInt(), cloudDusk = 0xFFFFE1CF.toInt(),
            rain = 0xFF3F5873.toInt(), snow = 0xFF8C99AA.toInt(), fog = 0xFFFFFFFF.toInt() to 0xFFF0F2F5.toInt(),
            hillFar = 0xFFDADDE2.toInt(), hillNear = 0xFFC5C9D0.toInt(), stars = 0xFF8C99AA.toInt(), birds = 0xFF1C1C21.toInt() to 0xFF1C1C21.toInt(),
            hillMix = 0.0,
        ),
    ),
    NEON(
        "neon", "Neon", light = false,
        ink = 0xFFFFFFFF.toInt(), inkDim = 0xD9FFFFFF.toInt(),
        accent = 0xFFFF4FA3.toInt(), onAccent = 0xFF1A0420.toInt(), accentDeep = 0xFF6A2BD9.toInt(),
        numberTop = 0xFFFFFFFF.toInt(), numberBottom = 0xFFFF9BD0.toInt(),
        calloutBg = 0xB314063A.toInt(), outline = 0xFF14063A.toInt(), glass = 0xB31A0B45.toInt(),
        cardTop = 0xFF2A0F66.toInt(), cardBottom = 0xFF120530.toInt(),
        daysCardTop = 0xFF2A0F66.toInt(), daysCardBottom = 0xFF120530.toInt(),
        scene = ScenePalette(
            skyTop = intArrayOf(0xFF0A0830.toInt(), 0xFF120C4A.toInt(), 0xFF22178A.toInt(), 0xFF2B2BC8.toInt(), 0xFF2F45E0.toInt(), 0xFF2D5BF0.toInt()),
            skyBottom = intArrayOf(0xFF2A0B55.toInt(), 0xFF4A0F70.toInt(), 0xFFB02A8F.toInt(), 0xFFFF4FA3.toInt(), 0xFFC64FE0.toInt(), 0xFF9A5BF5.toInt()),
            grayDay = 0xFF5B4BB0.toInt() to 0xFF9A6BD0.toInt(), grayNight = 0xFF140A3A.toInt() to 0xFF2A0F55.toInt(),
            sunCore = 0xFFFFE14D.toInt() to 0xFFFF8A3D.toInt(), sunGlow = 0xFFFF7AC6.toInt() to 0xFFFF4FA3.toInt(),
            moon = 0xFFFFF2FB.toInt(), moonDark = 0xFF3A1A7A.toInt(), moonGlow = 0xFFFF9BD0.toInt(),
            cloudClear = 0xFFE7D9FF.toInt(), cloudRain = 0xFF8F7AD9.toInt(), cloudNight = 0xFF4A2A9A.toInt(), cloudDusk = 0xFFFF9BD0.toInt(),
            rain = 0xFF7FE7FF.toInt(), snow = 0xFFFFFFFF.toInt(), fog = 0xFFE7D9FF.toInt() to 0xFF4A2A9A.toInt(),
            hillFar = 0xFF3B1470.toInt(), hillNear = 0xFF1E0A45.toInt(), stars = 0xFFFFF2FB.toInt(), birds = 0xFF1E0A45.toInt() to 0xFF0A0420.toInt(),
            hillMix = 0.0,
        ),
    );

    /** A widget card with dark text: the 3-day one on the default skin, and every card on a light skin. */
    fun cardLight(threeDays: Boolean): Boolean = light || (threeDays && this == GOLD)
    fun cardInk(threeDays: Boolean): Int = if (cardLight(threeDays)) 0xFF1C1C21.toInt() else ink
    fun cardMuted(threeDays: Boolean): Int = if (cardLight(threeDays)) 0x991C1C21.toInt() else inkDim
    fun cardColors(threeDays: Boolean): Pair<Int, Int> = if (threeDays) daysCardTop to daysCardBottom else cardTop to cardBottom

    companion object {
        val DEFAULT = GOLD
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
        /** The original scene: a real sky, gold sun, white clouds. */
        val DEFAULT = ScenePalette(
            skyTop = intArrayOf(0xFF050816.toInt(), 0xFF0B1230.toInt(), 0xFF1F2A5A.toInt(), 0xFF3C5A9A.toInt(), 0xFF4A7CC4.toInt(), 0xFF3A78D6.toInt()),
            skyBottom = intArrayOf(0xFF121A36.toInt(), 0xFF2B2A55.toInt(), 0xFFB0607A.toInt(), 0xFFF19A6A.toInt(), 0xFFF6C58A.toInt(), 0xFFA9D3F7.toInt()),
            grayDay = 0xFF7F8A96.toInt() to 0xFFB9C1CA.toInt(), grayNight = 0xFF12151B.toInt() to 0xFF22272F.toInt(),
            sunCore = 0xFFFFF4CC.toInt() to 0xFFFFC27A.toInt(), sunGlow = 0xFFFFE9A8.toInt() to 0xFFFF9E57.toInt(),
            moon = 0xFFF4ECCB.toInt(), moonDark = 0xFF2A3045.toInt(), moonGlow = 0xFFDCE4FF.toInt(),
            cloudClear = 0xFFF6F8FA.toInt(), cloudRain = 0xFF5E6670.toInt(), cloudNight = 0xFF2A303C.toInt(), cloudDusk = 0xFFF2A97E.toInt(),
            rain = 0xFFD6E6F5.toInt(), snow = 0xFFFFFFFF.toInt(), fog = 0xFFD9DDE2.toInt() to 0xFF3A4048.toInt(),
            hillFar = 0xFF0E1218.toInt(), hillNear = 0xFF080A0E.toInt(), stars = 0xFFFFFFFF.toInt(), birds = 0xFF2A2F3A.toInt() to 0xFF101318.toInt(),
        )
    }
}
