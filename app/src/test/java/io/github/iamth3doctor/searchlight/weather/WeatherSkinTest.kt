package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherSkinTest {

    private val frost = WeatherSkin.FROST

    /** Every sky the scene can show: each stop, top and bottom, and its overcast grays by day and night. */
    private fun skies(): List<Int> = with(frost.scene) {
        skyTop.toList() + skyBottom.toList() + listOf(grayDay.first, grayDay.second, grayNight.first, grayNight.second)
    }

    /** The chrome's colors: text, marks, panels, cards (not the weather). */
    private fun chrome(): List<Int> = with(frost) {
        listOf(ink, inkDim, accent, onAccent, accentDeep, numberTop, numberBottom, calloutBg, outline, glass,
            cardTop, cardBottom, daysCardTop, daysCardBottom)
    }

    private fun isGray(c: Int): Boolean {
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val b = c and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= 12
    }

    @Test fun oneLookFrostWhatever() {
        assertEquals(listOf(WeatherSkin.FROST), WeatherSkin.entries.toList())
        assertEquals(WeatherSkin.FROST, WeatherSkin.DEFAULT)
        // A skin saved by an earlier build (gold, ember, paper, neon) comes back as Frost.
        for (old in listOf(null, "gold", "ember", "paper", "neon")) assertEquals(WeatherSkin.FROST, WeatherSkin.of(old))
        assertTrue(frost.scene === ScenePalette.DEFAULT)
    }

    @Test fun theChromeIsMonochromeFrostedGlass() {
        for (c in chrome()) assertTrue("${Integer.toHexString(c)} is a gray", isGray(c))
        assertTrue(frost.light)
        assertTrue("dark text", WeatherSkin.luminance(frost.ink) < 0.02)
        for (c in listOf(frost.glass, frost.calloutBg, frost.cardTop, frost.cardBottom)) {
            assertTrue("${Integer.toHexString(c)}: translucent", (c ushr 24) in 0x80..0xF0)
            assertTrue("${Integer.toHexString(c)}: white", WeatherSkin.luminance(c or 0xFF000000.toInt()) > 0.8)
        }
    }

    @Test fun theWeatherInItsNaturalColors() = with(frost.scene) {
        fun r(c: Int) = c shr 16 and 0xFF
        fun g(c: Int) = c shr 8 and 0xFF
        fun b(c: Int) = c and 0xFF
        // Yellow sunlight.
        for (c in listOf(sunCore.second, sunGlow.first, sunGlow.second)) assertTrue("sun ${Integer.toHexString(c)}", r(c) > 230 && g(c) > 150 && b(c) < 160)
        // White clouds (gray under rain).
        assertTrue(r(cloudClear) > 245 && g(cloudClear) > 245 && b(cloudClear) > 245)
        assertTrue("rain clouds gray", isGray(cloudRain) && WeatherSkin.luminance(cloudRain) < WeatherSkin.luminance(cloudClear))
        // Blue rain, white snow.
        assertTrue("rain blue", b(rain) > r(rain) + 60 && b(rain) > g(rain))
        assertEquals(0xFFFFFFFF.toInt(), snow)
        // A blue day sky, a dark blue night.
        val day = skyTop.last()
        assertTrue("day sky blue", b(day) > r(day) + 100)
        val night = skyTop.first()
        assertTrue("night dark blue", WeatherSkin.luminance(night) < 0.01 && b(night) > r(night))
    }

    @Test fun textReadsOverEverySkyOnItsFrostedPad() {
        for (sky in skies()) {
            val under = WeatherSkin.over(frost.calloutBg, sky)
            assertTrue("text on ${Integer.toHexString(sky)}", WeatherSkin.contrast(frost.ink, under) >= 4.5)
            assertTrue("label on ${Integer.toHexString(sky)}", WeatherSkin.contrast(frost.accent, under) >= 3.0)
            val pill = WeatherSkin.over(frost.glass, sky)
            assertTrue("pill text on ${Integer.toHexString(sky)}", WeatherSkin.contrast(frost.ink, pill) >= 4.5)
        }
    }

    /** A callout or widget card can sit over anything, white sun to black wallpaper: its text still reads. */
    @Test fun frostedPanelsReadOverTheBrightestAndDarkest() {
        for (behind in listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt())) {
            for (panel in listOf(frost.calloutBg, frost.glass)) {
                val ratio = WeatherSkin.contrast(frost.ink, WeatherSkin.over(panel, behind))
                assertTrue("text over ${Integer.toHexString(panel)} on ${Integer.toHexString(behind)}: $ratio", ratio >= 4.5)
            }
            for (three in listOf(true, false)) {
                val (top, bottom) = frost.cardColors(three)
                for (card in listOf(top, bottom)) {
                    val under = WeatherSkin.over(card, behind)
                    assertTrue("card text", WeatherSkin.contrast(frost.cardInk(three), under) >= 7.0)
                    assertTrue("card accent", WeatherSkin.contrast(frost.accent, under) >= 4.5)
                }
            }
        }
        assertTrue(frost.cardLight(threeDays = true) && frost.cardLight(threeDays = false))
    }

    @Test fun contrastMath() {
        assertEquals(21.0, WeatherSkin.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(1.0, WeatherSkin.contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 0.0)
        assertEquals(0xFF808080.toInt() and 0xFFFEFEFE.toInt(), WeatherSkin.over(0x80FFFFFF.toInt(), 0xFF000000.toInt()) and 0xFFFEFEFE.toInt())
    }
}
