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

    /** Every color the look uses (the skin's own and the scene's). */
    private fun allColors(): List<Int> = with(frost) {
        listOf(ink, inkDim, accent, onAccent, accentDeep, numberTop, numberBottom, calloutBg, outline, glass,
            cardTop, cardBottom, daysCardTop, daysCardBottom)
    } + with(frost.scene) {
        skies() + listOf(sunCore.first, sunCore.second, sunGlow.first, sunGlow.second, moon, moonDark, moonGlow,
            cloudClear, cloudRain, cloudNight, cloudDusk, rain, snow, fog.first, fog.second, hillFar, hillNear, stars,
            birds.first, birds.second)
    }

    @Test fun oneLookFrostWhatever() {
        assertEquals(listOf(WeatherSkin.FROST), WeatherSkin.entries.toList())
        assertEquals(WeatherSkin.FROST, WeatherSkin.DEFAULT)
        // A skin saved by an earlier build (gold, ember, paper, neon) comes back as Frost.
        for (old in listOf(null, "gold", "ember", "paper", "neon")) assertEquals(WeatherSkin.FROST, WeatherSkin.of(old))
        assertTrue(frost.scene === ScenePalette.DEFAULT)
    }

    @Test fun noColorAnywhere() {
        for (c in allColors()) {
            val r = c shr 16 and 0xFF
            val g = c shr 8 and 0xFF
            val b = c and 0xFF
            assertTrue("${Integer.toHexString(c)} is a gray", maxOf(r, g, b) - minOf(r, g, b) <= 12)
        }
    }

    @Test fun frostedLightWithDarkText() {
        assertTrue(frost.light)
        assertTrue("pale skies", skies().all { WeatherSkin.luminance(it) > 0.4 })
        assertTrue("dark text", WeatherSkin.luminance(frost.ink) < 0.02)
        // The panels and pills are translucent white glass.
        for (c in listOf(frost.glass, frost.calloutBg, frost.cardTop, frost.cardBottom)) {
            val a = c ushr 24
            assertTrue("${Integer.toHexString(c)}: translucent", a in 0x80..0xF0)
            assertTrue("${Integer.toHexString(c)}: white", WeatherSkin.luminance(c or 0xFF000000.toInt()) > 0.8)
        }
    }

    @Test fun textReadsOverEverySky() {
        for (sky in skies()) {
            val under = WeatherSkin.over(frost.calloutBg, sky)
            assertTrue("text on ${Integer.toHexString(sky)}", WeatherSkin.contrast(frost.ink, under) >= 4.5)
            assertTrue("label on ${Integer.toHexString(sky)}", WeatherSkin.contrast(frost.accent, under) >= 3.0)
            // Straight on the sky (the big condition, the 7 DAYS handle): still reads.
            assertTrue("ink straight on ${Integer.toHexString(sky)}", WeatherSkin.contrast(frost.ink, sky) >= 4.5)
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
