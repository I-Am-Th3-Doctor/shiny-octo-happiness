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

    // ---- The "Glass transparency" setting ----

    private val walls = listOf(red, blue, 0xFFFFD54F.toInt(), 0xFFF5F5F5.toInt(), 0xFF202830.toInt(), 0xFF808080.toInt())
    private fun alpha(c: Int) = (c ushr 24) / 255f
    /** The fixed tints (tokens.xml): card, row, card and row without blur, popup, settings cards. */
    private val card = 0x800B0B0D.toInt()
    private val row = 0x590B0B0D
    private val stock = listOf(card, row, 0xBF0B0B0D.toInt(), 0x1FFFFFFF, 0xA60B0B0D.toInt(), 0xE6FFFFFF.toInt(), 0x990B0B0D.toInt())

    @Test fun theDefaultLevelIsTodaysLook() {
        for (c in stock) assertEquals(c, GlassTint.scale(c, GlassTint.LEVEL_DEFAULT))
        for (wall in walls) for (layer in GlassTint.Layer.values()) {
            assertEquals(GlassTint.tint(wall, layer), GlassTint.tint(wall, layer, GlassTint.LEVEL_DEFAULT))
        }
        assertEquals(0.5f, alpha(GlassTint.tint(0xFF202830.toInt(), GlassTint.Layer.CARD)), 0.01f)
    }

    @Test fun clearIsNearlyInvisibleAndSolidNearlyOpaque() {
        // The card's 50% and the row's 35%: about 10% or less at Clear.
        assertTrue(alpha(GlassTint.scale(card, 0)) <= 0.10f)
        assertTrue(alpha(GlassTint.scale(row, 0)) <= 0.10f)
        for (wall in walls) assertTrue(alpha(GlassTint.tint(wall, GlassTint.Layer.CARD, 0)) <= 0.13f)
        // Every surface clearly clearer, and nearly opaque at Solid (a hint of glass left).
        for (c in stock) {
            assertTrue(alpha(GlassTint.scale(c, 0)) <= alpha(c) * 0.15f)
            if (alpha(c) < 0.9f) assertTrue(alpha(GlassTint.scale(c, 100)) >= 0.9f)
            assertTrue(alpha(GlassTint.scale(c, 100)) <= 0.965f)
        }
        // The three looks the screenshots compare are far apart.
        assertTrue(alpha(GlassTint.scale(card, 50)) - alpha(GlassTint.scale(card, 0)) > 0.4f)
        assertTrue(alpha(GlassTint.scale(card, 100)) - alpha(GlassTint.scale(card, 50)) > 0.4f)
    }

    @Test fun theLevelScalesEveryTintTogether() {
        for (c in stock) {
            var last = -1f
            for (level in 0..100) {
                val a = alpha(GlassTint.scale(c, level))
                assertTrue("$level: $a after $last", a >= last)
                last = a
            }
        }
        for (wall in walls) {
            var last = -1f
            for (level in 0..100 step 5) {
                val a = alpha(GlassTint.tint(wall, GlassTint.Layer.CARD, level))
                assertTrue(a >= last)
                last = a
            }
        }
    }

    @Test fun theRowStaysLighterThanTheCardAtEveryLevel() {
        for (level in 0..100 step 5) {
            assertTrue("$level", alpha(GlassTint.scale(row, level)) < alpha(GlassTint.scale(card, level)))
            for (wall in walls) {
                val c = GlassTint.luminance(composite(GlassTint.tint(wall, GlassTint.Layer.CARD, level), wall))
                val r = GlassTint.luminance(composite(GlassTint.tint(wall, GlassTint.Layer.ROW, level), wall))
                assertTrue("level $level, wall ${Integer.toHexString(wall)}: row $r card $c", r >= c)
            }
        }
    }

    @Test fun textShadowOnlyTowardClear() {
        for (level in 50..100) assertEquals(0f, GlassTint.textShadow(level))
        assertEquals(1f, GlassTint.textShadow(0))
        assertTrue(GlassTint.textShadow(10) > GlassTint.textShadow(40))
    }

    @Test fun theBlurFadesTowardClearOnly() {
        for (level in 50..100) assertEquals(1f, GlassTint.blurOpacity(level))
        assertTrue(GlassTint.blurOpacity(0) <= 0.1f)
        var last = -1f
        for (level in 0..100) { val o = GlassTint.blurOpacity(level); assertTrue(o >= last); last = o }
    }
}
