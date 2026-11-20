package com.childsplay.launcher.weather

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val GInk = Color(0xFF1B0B3B)

/** A small, static, toy-styled weather icon for lists and the widget. */
@Composable
fun WeatherGlyph(code: Int, isDay: Boolean, size: Dp = 32.dp, modifier: Modifier = Modifier) {
    val cond = WeatherCodes.condition(code)
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val line = Stroke(s * 0.06f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun sun(c: Offset, r: Float) {
            for (i in 0 until 8) {
                val a = i * PI.toFloat() / 4f
                drawLine(Color(0xFFFF7A00), Offset(c.x + cos(a) * r * 1.3f, c.y + sin(a) * r * 1.3f),
                    Offset(c.x + cos(a) * r * 1.75f, c.y + sin(a) * r * 1.75f), s * 0.07f, StrokeCap.Round)
            }
            drawCircle(Color(0xFFFFD600), r, c)
            drawCircle(GInk, r, c, style = line)
        }
        fun moon(c: Offset, r: Float) {
            val p = Path.combine(
                PathOperation.Difference,
                Path().apply { addOval(Rect(c, r)) },
                Path().apply { addOval(Rect(Offset(c.x + r * 0.55f, c.y - r * 0.35f), r * 0.9f)) },
            )
            drawPath(p, Color(0xFFFFF176))
            drawPath(p, GInk, style = line)
        }
        fun cloud(o: Offset, w: Float, color: Color) {
            val h = w * 0.5f
            val p = listOf(
                Path().apply { addOval(Rect(Offset(o.x + w * 0.3f, o.y + h * 0.6f), w * 0.22f)) },
                Path().apply { addOval(Rect(Offset(o.x + w * 0.55f, o.y + h * 0.42f), w * 0.27f)) },
                Path().apply {
                    addRoundRect(androidx.compose.ui.geometry.RoundRect(o.x + w * 0.08f, o.y + h * 0.6f, o.x + w * 0.95f, o.y + h * 1.05f, h * 0.25f, h * 0.25f))
                },
            ).reduce { a, b -> Path.combine(PathOperation.Union, a, b) }
            drawPath(p, color)
            drawPath(p, GInk, style = line)
        }
        val orb = Offset(s * 0.36f, s * 0.34f)
        when (cond) {
            Condition.CLEAR -> if (isDay) sun(Offset(s / 2, s / 2), s * 0.24f) else moon(Offset(s / 2, s / 2), s * 0.3f)
            Condition.PARTLY -> {
                if (isDay) sun(orb, s * 0.18f) else moon(orb, s * 0.22f)
                cloud(Offset(s * 0.12f, s * 0.38f), s * 0.8f, Color.White)
            }
            Condition.CLOUDY -> {
                cloud(Offset(s * 0.02f, s * 0.12f), s * 0.6f, Color(0xFFE1F5FE))
                cloud(Offset(s * 0.18f, s * 0.36f), s * 0.8f, Color.White)
            }
            Condition.FOG -> {
                cloud(Offset(s * 0.1f, s * 0.1f), s * 0.8f, Color.White)
                for (i in 0..1) drawLine(GInk, Offset(s * 0.15f, s * (0.75f + i * 0.14f)), Offset(s * 0.85f, s * (0.75f + i * 0.14f)), s * 0.06f, StrokeCap.Round)
            }
            Condition.DRIZZLE, Condition.RAIN, Condition.SLEET, Condition.STORM -> {
                cloud(Offset(s * 0.08f, s * 0.04f), s * 0.84f, if (cond == Condition.STORM) Color(0xFFB388FF) else Color.White)
                val drops = if (cond == Condition.DRIZZLE) 2 else 3
                for (i in 0 until drops) {
                    val x = s * (0.3f + i * 0.2f)
                    drawLine(Color(0xFF00A8FF), Offset(x, s * 0.66f), Offset(x - s * 0.06f, s * 0.86f), s * 0.07f, StrokeCap.Round)
                }
                if (cond == Condition.STORM) {
                    val b = Path().apply {
                        moveTo(s * 0.55f, s * 0.55f); lineTo(s * 0.44f, s * 0.76f); lineTo(s * 0.56f, s * 0.76f); lineTo(s * 0.46f, s * 0.98f)
                    }
                    drawPath(b, Color(0xFFFFD600), style = Stroke(s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            Condition.SNOW -> {
                cloud(Offset(s * 0.08f, s * 0.04f), s * 0.84f, Color.White)
                for (i in 0 until 3) {
                    val c = Offset(s * (0.3f + i * 0.2f), s * (0.76f + (i % 2) * 0.1f))
                    drawCircle(Color.White, s * 0.06f, c)
                    drawCircle(GInk, s * 0.06f, c, style = Stroke(s * 0.035f))
                }
            }
        }
    }
}
