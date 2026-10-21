package io.github.iamth3doctor.searchlight.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxBlurTest {
    @Test fun flatColorStaysTheSame() {
        val px = IntArray(10 * 6) { 0xFF336699.toInt() }
        BoxBlur.blur(px, 10, 6, 2)
        assertTrue(px.all { it == 0xFF336699.toInt() })
    }

    @Test fun aBrightDotSpreadsOut() {
        val w = 9
        val px = IntArray(w * w) { 0xFF000000.toInt() }
        px[4 * w + 4] = 0xFFFFFFFF.toInt()
        BoxBlur.blur(px, w, w, 1)
        val center = px[4 * w + 4] and 0xFF
        val near = px[4 * w + 5] and 0xFF
        assertTrue("center dimmed: $center", center in 1 until 255)
        assertTrue("neighbour lit: $near", near > 0)
        assertEquals(0xFF, px[0] ushr 24) // alpha kept
    }
}
