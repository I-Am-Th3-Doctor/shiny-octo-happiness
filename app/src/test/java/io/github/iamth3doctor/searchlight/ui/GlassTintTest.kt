package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassTintTest {
    private val red = 0xFFE0453A.toInt()
    private val blue = 0xFF3A6FE0.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun hue(c: Int) = GlassTint.hsl(c).first

    /** What white text sits on: the tint (with its alpha) over the blurred wallpaper. */
    private fun composite(tint: Int, behind: Int): Int {
        val a = (tint ushr 24) / 255f
        fun ch(s: Int) = (((tint shr s) and 0xFF) * a + ((behind shr s) and 0xFF) * (1 - a)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun contrastWithWhite(c: Int) = (1.05f) / (GlassTint.luminance(c) + 0.05f)

    @Test fun keepsTheWallpapersHue() {
        for (wall in listOf(red, blue)) {
            val t = GlassTint.tint(wall, GlassTint.Layer.CARD)
            assertEquals(hue(wall), hue(t), 6f)
            assertTrue("visibly colored", GlassTint.hsl(t).second >= 0.3f)
        }
    }

    @Test fun whiteTextStaysReadableEvenOverBrightWallpaper() {
        for (wall in listOf(red, blue, 0xFFFFD54F.toInt(), 0xFFF5F5F5.toInt())) {
            val card = composite(GlassTint.tint(wall, GlassTint.Layer.CARD), wall)
            val row = composite(GlassTint.tint(wall, GlassTint.Layer.ROW), wall)
            assertTrue("card %.1f".format(contrastWithWhite(card)), contrastWithWhite(card) >= 4.5f)
            assertTrue("row %.1f".format(contrastWithWhite(row)), contrastWithWhite(row) >= 3.5f)
        }
    }

    @Test fun rowIsOneStepLighterThanTheCard() {
        val card = GlassTint.tint(red, GlassTint.Layer.CARD)
        val row = GlassTint.tint(red, GlassTint.Layer.ROW)
        assertTrue(GlassTint.hsl(row).third > GlassTint.hsl(card).third)
    }

    @Test fun greyWallpaperGivesGreyGlass() {
        val t = GlassTint.tint(0xFF808080.toInt(), GlassTint.Layer.CARD)
        assertTrue(GlassTint.hsl(t).second < 0.08f)
    }

    @Test fun sampleFavoursColorOverGrey() {
        val strip = IntArray(100) { if (it < 20) red else 0xFF777777.toInt() }
        val s = GlassTint.sample(strip)!!
        assertEquals(hue(red), hue(s), 8f)
        assertNull(GlassTint.sample(IntArray(0)))
        assertEquals(white, GlassTint.sample(intArrayOf(white)))
    }
}
