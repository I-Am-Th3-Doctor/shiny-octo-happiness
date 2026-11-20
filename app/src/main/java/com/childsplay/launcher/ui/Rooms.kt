package com.childsplay.launcher.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.random.Random

/** A home backdrop: a loud base colour and a big pattern. Drawn once per size, then cached. */
data class Room(val name: String, val base: Color, val darkIcons: Boolean, val pattern: DrawScope.() -> Unit)

object Rooms {
    val all = listOf(
        Room("Grape Dots", Toy.Purple, false) {
            val step = size.width / 5f
            var row = 0
            var y = -step / 2
            while (y < size.height + step) {
                var x = if (row % 2 == 0) 0f else step / 2
                while (x < size.width + step) {
                    drawCircle(Toy.Pink, step * 0.18f, Offset(x, y))
                    x += step
                }
                y += step * 0.9f; row++
            }
        },
        Room("Sunny Stripes", Toy.Yellow, true) {
            val band = size.width / 6f
            rotate(-30f, Offset(size.width / 2, size.height / 2)) {
                var x = -size.height
                while (x < size.width + size.height) {
                    drawRect(Toy.Orange, Offset(x, -size.height), Size(band * 0.5f, size.height * 3))
                    x += band
                }
            }
        },
        Room("Confetti", Toy.Teal, true) {
            val r = Random(42)
            val colors = listOf(Toy.Yellow, Toy.Pink, Toy.Purple, Toy.Red, Color.White, Toy.Blue)
            repeat(140) {
                val c = Offset(r.nextFloat() * size.width, r.nextFloat() * size.height)
                val len = size.width * (0.025f + r.nextFloat() * 0.03f)
                rotate(r.nextFloat() * 180f, c) {
                    drawLine(colors[it % colors.size], c - Offset(len, 0f), c + Offset(len, 0f), size.width * 0.014f, StrokeCap.Round)
                }
            }
        },
        Room("Cherry Check", Toy.Red, false) {
            val cell = size.width / 6f
            var y = 0f
            var row = 0
            while (y < size.height) {
                var x = if (row % 2 == 0) 0f else cell
                while (x < size.width) {
                    drawRect(Toy.Pink, Offset(x, y), Size(cell, cell))
                    x += cell * 2
                }
                y += cell; row++
            }
        },
        Room("Sky Squiggles", Toy.Sky, false) {
            val stroke = Stroke(size.width * 0.018f, cap = StrokeCap.Round)
            val gap = size.width / 4.5f
            var y = gap / 2
            var i = 0
            while (y < size.height + gap) {
                val p = Path().apply {
                    moveTo(-20f, y)
                    var x = -20f
                    while (x < size.width + 40f) {
                        quadraticTo(x + gap / 4, y - gap / 4, x + gap / 2, y)
                        quadraticTo(x + 3 * gap / 4, y + gap / 4, x + gap, y)
                        x += gap
                    }
                }
                drawPath(p, if (i % 2 == 0) Color.White else Toy.Yellow, style = stroke)
                y += gap * 0.75f; i++
            }
        },
        Room("Lime Zigzag", Toy.Lime, true) {
            val stroke = Stroke(size.width * 0.03f)
            val w = size.width / 6f
            var y = 0f
            while (y < size.height + w) {
                val p = Path().apply {
                    moveTo(0f, y)
                    var x = 0f
                    var up = true
                    while (x < size.width + w) {
                        x += w / 2
                        lineTo(x, if (up) y - w / 3 else y)
                        up = !up
                    }
                }
                drawPath(p, Toy.Green, style = stroke)
                y += w
            }
        },
    )

    fun get(i: Int) = all[i.mod(all.size)]
}

@Composable
fun RoomBackdrop(room: Room, modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            onDrawBehind {
                drawRect(room.base)
                room.pattern(this)
            }
        }
    )
}
