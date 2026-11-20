package com.something.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A pixel glyph on an [n]×[n] dot grid. Cells are 0 (off), 1 (white) or 2 (red accent).
 * Glyphs are generated from simple geometry sampled at cell centres, so they stay crisp and
 * consistent at any size.
 */
class Glyph(val n: Int, val cells: IntArray) {
    operator fun get(x: Int, y: Int) = cells[y * n + x]
}

private class GlyphBuilder(val n: Int = 15) {
    val cells = IntArray(n * n)
    fun set(x: Int, y: Int, v: Int = 1) { if (x in 0 until n && y in 0 until n) cells[y * n + x] = v }
    fun clear(x: Int, y: Int) = set(x, y, 0)
    fun each(f: (x: Int, y: Int) -> Unit) { for (y in 0 until n) for (x in 0 until n) f(x, y) }
    fun disk(cx: Float, cy: Float, r: Float, v: Int = 1) = each { x, y -> if (hypot(x - cx, y - cy) <= r) set(x, y, v) }
    fun carve(cx: Float, cy: Float, r: Float) = each { x, y -> if (hypot(x - cx, y - cy) <= r) clear(x, y) }
    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, v: Int = 1) { for (y in y0..y1) for (x in x0..x1) set(x, y, v) }
    fun build() = Glyph(n, cells)

    /** A puffy cloud whose flat base sits on row [base]; [dx] shifts it sideways. */
    fun cloud(base: Int, dx: Float = 0f, scale: Float = 1f) {
        val b = base.toFloat()
        disk(4.5f + dx, b - 2.2f * scale, 2.4f * scale)
        disk(8f + dx, b - 3.6f * scale, 3.3f * scale)
        disk(11.2f + dx, b - 2f * scale, 2.2f * scale)
        rect((2.5f + dx).toInt(), (b - 2 * scale).toInt(), (12.8f + dx).toInt(), base)
    }

    fun sunRays(cx: Float, cy: Float, r0: Float, r1: Float) {
        for (i in 0 until 8) {
            val a = i * Math.PI / 4
            var r = r0
            while (r <= r1) {
                set(Math.round(cx + cos(a) * r).toInt(), Math.round(cy + sin(a) * r).toInt())
                r += 1f
            }
        }
    }
}

object Glyphs {
    val Sun: Glyph = GlyphBuilder().apply { disk(7f, 7f, 3.2f); sunRays(7f, 7f, 5f, 6.4f) }.build()

    val Moon: Glyph = GlyphBuilder().apply { disk(7f, 7f, 5.6f); carve(10.2f, 4.6f, 4.6f) }.build()

    val Cloud: Glyph = GlyphBuilder().apply { cloud(11) }.build()

    val Cloudy: Glyph = GlyphBuilder().apply {
        cloud(8, dx = 1.5f, scale = 0.75f)
        each { x, y -> if (cells[y * n + x] == 1 && (x + y) % 2 == 1) clear(x, y) } // back cloud is half-lit
        cloud(12, dx = -0.5f)
    }.build()

    /** Sky body behind a front cloud: the body is cleared one dot around the cloud so they read apart. */
    private fun behindCloud(body: GlyphBuilder.() -> Unit) = GlyphBuilder().apply {
        body()
        val front = GlyphBuilder().apply { cloud(14, dx = 1.2f, scale = 0.85f) }
        each { x, y ->
            val near = (-1..1).any { dy -> (-1..1).any { dx ->
                val nx = x + dx; val ny = y + dy
                nx in 0 until n && ny in 0 until n && front.cells[ny * n + nx] != 0
            } }
            if (near) clear(x, y)
        }
        each { x, y -> if (front.cells[y * n + x] != 0) set(x, y) }
    }.build()

    val PartlyDay: Glyph = behindCloud { disk(5f, 5f, 2.4f); sunRays(5f, 5f, 3.8f, 4.6f) }

    val PartlyNight: Glyph = behindCloud { disk(5.5f, 5.5f, 4.6f); carve(8.6f, 3.4f, 3.8f) }

    val Fog: Glyph = GlyphBuilder().apply {
        for ((row, off) in listOf(3 to 0, 6 to 2, 9 to 1, 12 to 3)) {
            for (x in off until 15 - (3 - off)) if ((x + row) % 4 != 0) set(x, row)
        }
    }.build()

    private fun rainy(drops: List<Pair<Int, Int>>, cloudValue: Int = 1, extra: GlyphBuilder.() -> Unit = {}) =
        GlyphBuilder().apply {
            cloud(8)
            if (cloudValue != 1) each { x, y -> if (cells[y * n + x] == 1) set(x, y, cloudValue) }
            drops.forEach { (x, y) -> set(x, y); set(x - 1, y + 1) }
            extra()
        }.build()

    val Drizzle: Glyph = rainy(listOf(5 to 10, 10 to 11))
    val Rain: Glyph = rainy(listOf(4 to 10, 8 to 10, 12 to 10, 6 to 13, 10 to 13))
    val Snow: Glyph = GlyphBuilder().apply {
        cloud(8)
        listOf(4 to 10, 9 to 10, 12 to 12, 6 to 12, 3 to 14, 10 to 14).forEach { (x, y) -> set(x, y) }
    }.build()
    val Sleet: Glyph = rainy(listOf(4 to 10, 11 to 10)) { listOf(8 to 11, 5 to 13, 12 to 13).forEach { (x, y) -> set(x, y) } }

    /** Thunderstorm: the bolt is the red accent. */
    val Storm: Glyph = GlyphBuilder().apply {
        cloud(8)
        listOf(8 to 9, 7 to 10, 6 to 11, 7 to 11, 8 to 11, 7 to 12, 6 to 13, 5 to 14).forEach { (x, y) -> set(x, y, 2) }
    }.build()

    /** Alert triangle, all red. */
    val Alert: Glyph = GlyphBuilder().apply {
        for (y in 1..13) {
            val half = (y - 1) / 2f
            for (x in 0 until 15) if (kotlin.math.abs(x - 7f) <= half) set(x, y, 2)
        }
        for (y in 5..9) clear(7, y)
        clear(7, 11)
    }.build()
}

/**
 * Draw a [Glyph] as round dots. [unlit] draws faint dots in off cells, for an LED-panel look.
 */
@Composable
fun PixelGlyph(
    glyph: Glyph,
    size: Dp,
    modifier: Modifier = Modifier,
    color: Color = Ink.White,
    accent: Color = Ink.Red,
    unlit: Color? = null,
) {
    Canvas(modifier.size(size)) {
        val cell = this.size.minDimension / glyph.n
        val r = cell * 0.42f
        for (y in 0 until glyph.n) for (x in 0 until glyph.n) {
            val c = Offset((x + 0.5f) * cell, (y + 0.5f) * cell)
            when (glyph[x, y]) {
                1 -> drawCircle(color, r, c)
                2 -> drawCircle(accent, r, c)
                else -> if (unlit != null) drawCircle(unlit, cell * 0.16f, c)
            }
        }
    }
}
