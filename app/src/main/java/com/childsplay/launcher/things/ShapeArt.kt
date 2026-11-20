package com.childsplay.launcher.things

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import com.childsplay.launcher.data.ThingShape
import com.childsplay.launcher.ui.Toy

/**
 * Geometry for a shape drawn into a box of a given size. [window] is where app icons peek out,
 * in pixels. [silhouette] gets the hard toy shadow and the ink outline.
 */
class ShapeGeometry(
    val silhouette: Path,
    val window: Rect,
    val windowIsRound: Boolean,
    val draw: DrawScope.(color: Color) -> Unit,
)

fun tint(c: Color, amount: Float) = lerp(c, Color.White, amount)
fun shade(c: Color, amount: Float) = lerp(c, Toy.Ink, amount)

/** Build shape geometry for [shape] in a box of [size]. Called from drawWithCache, so once per size. */
fun shapeGeometry(shape: ThingShape, size: Size): ShapeGeometry {
    val w = size.width
    val h = size.height
    val s = minOf(w, h)
    // Keep shapes square-ish and centred in the box.
    val ox = (w - s) / 2f
    val oy = (h - s) / 2f
    fun x(f: Float) = ox + f * s
    fun y(f: Float) = oy + f * s
    fun r(l: Float, t: Float, rr: Float, b: Float) = Rect(x(l), y(t), x(rr), y(b))
    val stroke = Stroke(width = s * 0.032f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val thin = Stroke(width = s * 0.02f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    fun circle(cx: Float, cy: Float, rad: Float) = Path().apply {
        addOval(Rect(Offset(x(cx), y(cy)), rad * s))
    }
    fun rrect(l: Float, t: Float, rr: Float, b: Float, rad: Float) = Path().apply {
        addRoundRect(RoundRect(r(l, t, rr, b), CornerRadius(rad * s)))
    }
    fun union(vararg paths: Path): Path = paths.reduce { acc, p -> Path.combine(PathOperation.Union, acc, p) }

    return when (shape) {
        ThingShape.CLOUD -> {
            val sil = union(
                circle(0.28f, 0.56f, 0.2f),
                circle(0.46f, 0.38f, 0.25f),
                circle(0.68f, 0.43f, 0.2f),
                circle(0.8f, 0.62f, 0.14f),
                rrect(0.1f, 0.5f, 0.92f, 0.82f, 0.16f),
            )
            ShapeGeometry(sil, r(0.22f, 0.4f, 0.8f, 0.8f), false) { c ->
                drawPath(sil, c)
                // Puffy highlights.
                drawCircle(tint(c, 0.55f), radius = s * 0.07f, center = Offset(x(0.38f), y(0.26f)))
                drawCircle(tint(c, 0.55f), radius = s * 0.035f, center = Offset(x(0.5f), y(0.21f)))
                drawPath(sil, Toy.Ink, style = stroke)
            }
        }

        ThingShape.TV -> {
            val body = rrect(0.06f, 0.26f, 0.94f, 0.86f, 0.1f)
            val legs = union(rrect(0.18f, 0.8f, 0.27f, 0.96f, 0.03f), rrect(0.73f, 0.8f, 0.82f, 0.96f, 0.03f))
            val sil = union(body, legs)
            val screen = r(0.13f, 0.33f, 0.71f, 0.79f)
            ShapeGeometry(sil, screen.deflate(s * 0.03f), false) { c ->
                // Antennae.
                drawLine(Toy.Ink, Offset(x(0.5f), y(0.27f)), Offset(x(0.3f), y(0.06f)), s * 0.03f, StrokeCap.Round)
                drawLine(Toy.Ink, Offset(x(0.5f), y(0.27f)), Offset(x(0.72f), y(0.08f)), s * 0.03f, StrokeCap.Round)
                drawCircle(Toy.Yellow, s * 0.04f, Offset(x(0.3f), y(0.06f)))
                drawCircle(Toy.Ink, s * 0.04f, Offset(x(0.3f), y(0.06f)), style = thin)
                drawCircle(Toy.Pink, s * 0.04f, Offset(x(0.72f), y(0.08f)))
                drawCircle(Toy.Ink, s * 0.04f, Offset(x(0.72f), y(0.08f)), style = thin)
                drawPath(legs, shade(c, 0.35f))
                drawPath(body, c)
                // Screen: deep blue glass-free CRT with a bright rim.
                drawRoundRect(Color(0xFF241A6B), screen.topLeft, screen.size, CornerRadius(s * 0.07f))
                drawRoundRect(Toy.Ink, screen.topLeft, screen.size, CornerRadius(s * 0.07f), style = stroke)
                drawLine(
                    Color.White.copy(alpha = 0.35f), Offset(screen.left + s * 0.06f, screen.top + s * 0.05f),
                    Offset(screen.left + s * 0.16f, screen.top + s * 0.05f), s * 0.025f, StrokeCap.Round,
                )
                // Knobs and speaker.
                drawCircle(Toy.Yellow, s * 0.052f, Offset(x(0.83f), y(0.41f)))
                drawCircle(Toy.Ink, s * 0.052f, Offset(x(0.83f), y(0.41f)), style = thin)
                drawCircle(Toy.Lime, s * 0.042f, Offset(x(0.83f), y(0.56f)))
                drawCircle(Toy.Ink, s * 0.042f, Offset(x(0.83f), y(0.56f)), style = thin)
                for (i in 0..2) {
                    val yy = y(0.67f + i * 0.045f)
                    drawLine(Toy.Ink, Offset(x(0.77f), yy), Offset(x(0.89f), yy), s * 0.018f, StrokeCap.Round)
                }
                drawPath(sil, Toy.Ink, style = stroke)
            }
        }

        ThingShape.PAINTING -> {
            val frame = r(0.05f, 0.14f, 0.95f, 0.92f)
            val sil = Path().apply { addRect(frame) }
            val canvas = r(0.15f, 0.24f, 0.85f, 0.82f)
            ShapeGeometry(sil, canvas.deflate(s * 0.03f), false) { c ->
                // Hanging wire and nail.
                drawLine(Toy.Ink, Offset(x(0.22f), y(0.15f)), Offset(x(0.5f), y(0.03f)), s * 0.015f)
                drawLine(Toy.Ink, Offset(x(0.78f), y(0.15f)), Offset(x(0.5f), y(0.03f)), s * 0.015f)
                drawCircle(Toy.Ink, s * 0.025f, Offset(x(0.5f), y(0.03f)))
                // Gilded frame, bright not brassy.
                drawRect(Toy.Yellow, frame.topLeft, frame.size)
                drawRect(Toy.Orange, frame.deflate(s * 0.045f).topLeft, frame.deflate(s * 0.045f).size, style = thin)
                // Canvas with abstract art in the thing's colour.
                drawRect(tint(c, 0.75f), canvas.topLeft, canvas.size)
                clipPath(Path().apply { addRect(canvas) }) {
                    drawCircle(c, s * 0.2f, Offset(x(0.78f), y(0.3f)))
                    drawCircle(Toy.Pink.takeIf { it != c } ?: Toy.Sky, s * 0.13f, Offset(x(0.2f), y(0.8f)))
                    val squiggle = Path().apply {
                        moveTo(x(0.12f), y(0.55f))
                        cubicTo(x(0.3f), y(0.38f), x(0.45f), y(0.75f), x(0.62f), y(0.55f))
                        cubicTo(x(0.72f), y(0.44f), x(0.82f), y(0.62f), x(0.9f), y(0.5f))
                    }
                    drawPath(squiggle, shade(c, 0.25f), style = Stroke(s * 0.035f, cap = StrokeCap.Round))
                }
                drawRect(Toy.Ink, canvas.topLeft, canvas.size, style = thin)
                drawPath(sil, Toy.Ink, style = stroke)
            }
        }

        ThingShape.STATUE -> {
            val head = Path().apply { addOval(r(0.37f, 0.04f, 0.63f, 0.34f)) }
            val neck = rrect(0.44f, 0.28f, 0.56f, 0.42f, 0.02f)
            val shoulders = Path().apply {
                moveTo(x(0.2f), y(0.54f))
                cubicTo(x(0.2f), y(0.42f), x(0.32f), y(0.37f), x(0.5f), y(0.37f))
                cubicTo(x(0.68f), y(0.37f), x(0.8f), y(0.42f), x(0.8f), y(0.54f))
                close()
            }
            val bust = union(head, neck, shoulders)
            val cap = rrect(0.1f, 0.52f, 0.9f, 0.6f, 0.02f)
            val column = Path().apply { addRect(r(0.16f, 0.58f, 0.84f, 0.9f)) }
            val base = rrect(0.08f, 0.88f, 0.92f, 0.97f, 0.02f)
            val plinth = union(cap, column, base)
            val sil = union(bust, plinth)
            ShapeGeometry(sil, r(0.2f, 0.62f, 0.8f, 0.87f), false) { c ->
                drawPath(plinth, Toy.Paper)
                // Classical fluting, in colour.
                for (i in 0..4) {
                    val xx = x(0.22f + i * 0.14f)
                    drawLine(tint(c, 0.6f), Offset(xx, y(0.61f)), Offset(xx, y(0.87f)), s * 0.03f)
                }
                drawPath(cap, Toy.Ink, style = thin)
                drawPath(base, Toy.Ink, style = thin)
                drawPath(bust, c)
                // Curls of hair and a cheeky highlight.
                listOf(0.4f to 0.08f, 0.47f to 0.05f, 0.54f to 0.05f, 0.61f to 0.09f).forEach { (cx, cy) ->
                    drawCircle(shade(c, 0.25f), s * 0.045f, Offset(x(cx), y(cy)))
                }
                drawCircle(tint(c, 0.5f), s * 0.03f, Offset(x(0.44f), y(0.2f)))
                drawLine(Toy.Ink, Offset(x(0.46f), y(0.26f)), Offset(x(0.54f), y(0.26f)), s * 0.015f, StrokeCap.Round)
                drawPath(sil, Toy.Ink, style = stroke)
            }
        }

        ThingShape.BALLOON -> {
            val envelope = Path().apply {
                moveTo(x(0.5f), y(0.02f))
                cubicTo(x(0.98f), y(0.02f), x(0.96f), y(0.5f), x(0.64f), y(0.72f))
                lineTo(x(0.36f), y(0.72f))
                cubicTo(x(0.04f), y(0.5f), x(0.02f), y(0.02f), x(0.5f), y(0.02f))
                close()
            }
            val basket = rrect(0.37f, 0.8f, 0.63f, 0.97f, 0.03f)
            val sil = union(envelope, basket)
            ShapeGeometry(sil, r(0.26f, 0.16f, 0.74f, 0.56f), false) { c ->
                drawLine(Toy.Ink, Offset(x(0.37f), y(0.72f)), Offset(x(0.39f), y(0.8f)), s * 0.015f)
                drawLine(Toy.Ink, Offset(x(0.63f), y(0.72f)), Offset(x(0.61f), y(0.8f)), s * 0.015f)
                drawPath(envelope, c)
                clipPath(envelope) {
                    // Bold candy stripes.
                    for (i in -1..1 step 2) {
                        val stripe = Path().apply {
                            moveTo(x(0.5f), y(0.02f))
                            cubicTo(x(0.5f + i * 0.3f), y(0.15f), x(0.5f + i * 0.28f), y(0.5f), x(0.5f + i * 0.1f), y(0.75f))
                            lineTo(x(0.5f + i * 0.2f), y(0.75f))
                            cubicTo(x(0.5f + i * 0.4f), y(0.5f), x(0.5f + i * 0.42f), y(0.12f), x(0.5f), y(0.02f))
                        }
                        drawPath(stripe, Toy.Yellow)
                    }
                }
                drawPath(basket, Toy.Orange)
                drawLine(Toy.Ink, Offset(x(0.37f), y(0.87f)), Offset(x(0.63f), y(0.87f)), s * 0.012f)
                drawPath(sil, Toy.Ink, style = stroke)
            }
        }

        ThingShape.ROCKET -> {
            val body = Path().apply {
                moveTo(x(0.5f), y(0.02f))
                cubicTo(x(0.74f), y(0.14f), x(0.76f), y(0.42f), x(0.72f), y(0.8f))
                lineTo(x(0.28f), y(0.8f))
                cubicTo(x(0.24f), y(0.42f), x(0.26f), y(0.14f), x(0.5f), y(0.02f))
                close()
            }
            val finL = Path().apply {
                moveTo(x(0.28f), y(0.52f)); lineTo(x(0.1f), y(0.78f)); lineTo(x(0.12f), y(0.88f)); lineTo(x(0.29f), y(0.78f)); close()
            }
            val finR = Path().apply {
                moveTo(x(0.72f), y(0.52f)); lineTo(x(0.9f), y(0.78f)); lineTo(x(0.88f), y(0.88f)); lineTo(x(0.71f), y(0.78f)); close()
            }
            val sil = union(body, finL, finR)
            val porthole = Rect(Offset(x(0.5f), y(0.43f)), s * 0.19f)
            ShapeGeometry(sil, porthole.deflate(s * 0.045f), true) { c ->
                // Flame.
                val flame = Path().apply {
                    moveTo(x(0.36f), y(0.8f))
                    quadraticTo(x(0.5f), y(1.08f), x(0.64f), y(0.8f))
                    close()
                }
                drawPath(flame, Toy.Orange)
                drawPath(Path().apply {
                    moveTo(x(0.42f), y(0.8f)); quadraticTo(x(0.5f), y(0.98f), x(0.58f), y(0.8f)); close()
                }, Toy.Yellow)
                drawPath(finL, Toy.Red.takeIf { it != c } ?: Toy.Purple)
                drawPath(finR, Toy.Red.takeIf { it != c } ?: Toy.Purple)
                drawPath(body, c)
                // Nose cone band.
                clipPath(body) { drawRect(Toy.Paper, r(0f, 0f, 1f, 0.17f).topLeft, r(0f, 0f, 1f, 0.17f).size) }
                drawCircle(Color(0xFF241A6B), porthole.width / 2f, porthole.center)
                drawCircle(Toy.Yellow, porthole.width / 2f, porthole.center, style = Stroke(s * 0.045f))
                drawCircle(Toy.Ink, porthole.width / 2f + s * 0.022f, porthole.center, style = thin)
                drawPath(sil, Toy.Ink, style = stroke)
            }
        }
    }
}

/** Draws the hard, offset "sticker" shadow that makes things feel like physical toys. */
fun DrawScope.drawToyShadow(silhouette: Path, size: Size) {
    val s = minOf(size.width, size.height)
    translate(s * 0.035f, s * 0.045f) { drawPath(silhouette, Toy.Ink) }
}
