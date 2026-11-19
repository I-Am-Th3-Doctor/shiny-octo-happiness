package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherSkinTest {

    /** Every sky a skin can show: each stop, top and bottom, and its overcast grays by day and night. */
    private fun skies(s: WeatherSkin): List<Int> = with(s.scene) {
        skyTop.toList() + skyBottom.toList() + listOf(grayDay.first, grayDay.second, grayNight.first, grayNight.second)
    }

    @Test fun atLeastFourSkinsDefaultGold() {
        assertTrue(WeatherSkin.entries.size >= 4)
        assertEquals(WeatherSkin.GOLD, WeatherSkin.DEFAULT)
        assertEquals(WeatherSkin.GOLD, WeatherSkin.of(null))
        assertEquals(WeatherSkin.GOLD, WeatherSkin.of("gone"))
        for (s in WeatherSkin.entries) assertEquals(s, WeatherSkin.of(s.key))
        assertEquals(WeatherSkin.entries.size, WeatherSkin.entries.map { it.key }.toSet().size)
        // The default keeps the app's gold and the original scene.
        assertEquals(0xFFFFC93C.toInt(), WeatherSkin.GOLD.accent)
        assertTrue(WeatherSkin.GOLD.scene === ScenePalette.DEFAULT)
    }

    @Test fun theThreeOthersAreWhatWasAskedFor() {
        // A dark theme with a bright orange sun and white text.
        val ember = WeatherSkin.EMBER
        assertFalse(ember.light)
        assertEquals(0xFFFFFFFF.toInt(), ember.ink)
        assertTrue("dark skies", skies(ember).all { WeatherSkin.luminance(it) < 0.05 })
        val sun = ember.scene.sunCore.first
        assertTrue("orange sun", (sun shr 16 and 0xFF) > 0xE0 && (sun shr 8 and 0xFF) in 0x50..0xA0 && (sun and 0xFF) < 0x40)
        // A light theme: a white background, dark text.
        val paper = WeatherSkin.PAPER
        assertTrue(paper.light)
        assertTrue("white skies", skies(paper).all { WeatherSkin.luminance(it) > 0.7 })
        assertTrue("dark text", WeatherSkin.luminance(paper.ink) < 0.02)
        // Colorful: blue, purple and pink.
        val neon = WeatherSkin.NEON
        fun hue(c: Int): Float = FloatArray(3).also { java.awt.Color.RGBtoHSB(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF, it) }[0] * 360
        val hues = (neon.scene.skyTop.toList() + neon.scene.skyBottom.toList() + neon.accent + neon.accentDeep).map(::hue)
        assertTrue("blue: $hues", hues.any { it in 215f..245f })
        assertTrue("purple: $hues", hues.any { it in 255f..290f })
        assertTrue("pink: $hues", hues.any { it in 310f..340f })
    }

    @Test fun textReadsOverEverySkyOnEverySkin() {
        for (s in WeatherSkin.entries) for (sky in skies(s)) {
            val under = WeatherSkin.over(s.calloutBg, sky)
            val ink = WeatherSkin.contrast(s.ink, under)
            assertTrue("${s.title}: text on ${Integer.toHexString(sky)} is $ink", ink >= 4.5)
            val label = WeatherSkin.contrast(s.accent, under)
            assertTrue("${s.title}: label on ${Integer.toHexString(sky)} is $label", label >= 3.0)
        }
    }

    @Test fun lightTextOnDarkSkinsDarkOnLight() {
        for (s in WeatherSkin.entries) {
            if (s.light) assertTrue(s.title, WeatherSkin.luminance(s.ink) < 0.05)
            else assertTrue(s.title, WeatherSkin.luminance(s.ink) > 0.9)
        }
    }

    @Test fun widgetCardsRead() {
        for (s in WeatherSkin.entries) for (three in listOf(true, false)) {
            val (top, bottom) = s.cardColors(three)
            for (card in listOf(top, bottom)) {
                val c = WeatherSkin.contrast(s.cardInk(three), card)
                assertTrue("${s.title} ${if (three) "3-day" else "today"}: $c", c >= 7.0)
                // The wide card's condition is in the accent.
                if (!three) assertTrue("${s.title} accent", WeatherSkin.contrast(s.accent, card) >= 3.0)
            }
        }
        // The default skin's cards are as they were: the 3-day light, the wide dark.
        assertTrue(WeatherSkin.GOLD.cardLight(threeDays = true))
        assertFalse(WeatherSkin.GOLD.cardLight(threeDays = false))
        assertTrue(WeatherSkin.PAPER.cardLight(threeDays = false))
    }

    @Test fun contrastMath() {
        assertEquals(21.0, WeatherSkin.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, WeatherSkin.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.0)
        assertEquals(0xFF808080.toInt() and 0xFFFEFEFE.toInt(), WeatherSkin.over(0x80FFFFFF.toInt(), 0xFF000000.toInt()) and 0xFFFEFEFE.toInt())
    }
}
