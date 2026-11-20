package com.childsplay.launcher.weather

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.runtime.withFrameNanos
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

private val Ink = Color(0xFF1B0B3B)

/** Sky colours for a condition, so text on top can pick a readable colour. */
fun skyColors(condition: Condition, isDay: Boolean, skin: Skin): Pair<Color, Color> {
    val wet = condition in setOf(Condition.RAIN, Condition.STORM, Condition.SLEET, Condition.DRIZZLE)
    val grey = condition == Condition.CLOUDY || condition == Condition.FOG || condition == Condition.SNOW
    var top = if (isDay) skin.dayTop else skin.nightTop
    var bottom = if (isDay) skin.dayBottom else skin.nightBottom
    if (wet) {
        val k = if (condition == Condition.DRIZZLE) 0.55f else 0.85f
        top = lerp(top, skin.stormTop, k)
        bottom = lerp(bottom, skin.stormBottom, k)
    } else if (grey) {
        top = lerp(top, skin.stormTop, 0.35f)
        bottom = lerp(bottom, skin.stormBottom, 0.25f)
    }
    return top to bottom
}

private class Particle(val x: Float, val y: Float, val speed: Float, val size: Float, val phase: Float)

/**
 * The living backdrop: a toy diorama whose sky, sun or moon, clouds, precipitation and lightning
 * follow the current conditions. Everything is computed from elapsed time, so there is no
 * per-particle state and the draw is cheap. Only the draw phase re-runs each frame.
 */
@Composable
fun LivingScene(
    condition: Condition,
    isDay: Boolean,
    skin: Skin,
    windKmh: Double,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    animate: Boolean = true,
) {
    val time = remember { mutableFloatStateOf(3.2f) }
    val inPreview = LocalInspectionMode.current
    if (animate && !inPreview) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            while (true) {
                withFrameNanos { now -> time.floatValue = 3.2f + (now - start) / 1_000_000_000f }
            }
        }
    }
    val seed = remember(condition) { condition.ordinal * 7919 + 13 }
    val density = if (compact) 0.45f else 1f
    val rain = remember(seed, compact) { particles(seed, (150 * density).toInt()) }
    val flakes = remember(seed, compact) { particles(seed + 1, (90 * density).toInt()) }
    val stars = remember(seed, compact) { particles(seed + 2, (60 * density).toInt()) }
    val clouds = remember(seed) { particles(seed + 3, 8) }
    val wind = windKmh.toFloat().coerceIn(0f, 80f)

    Canvas(modifier) {
        val t = time.floatValue
        val (top, bottom) = skyColors(condition, isDay, skin)
        drawRect(Brush.verticalGradient(listOf(top, bottom)))

        val m = min(size.width, size.height)
        val ground = size.height * if (compact) 0.82f else 0.8f

        // Sun or moon.
        val showOrb = condition in setOf(Condition.CLEAR, Condition.PARTLY, Condition.CLOUDY, Condition.FOG)
        if (showOrb) {
            val c = Offset(size.width * if (compact) 0.5f else 0.76f, size.height * if (compact) 0.3f else 0.17f)
            val r = m * if (compact) 0.17f else 0.12f
            if (isDay) drawSun(c, r, t, skin) else drawMoon(c, r)
        }
        if (!isDay && condition in setOf(Condition.CLEAR, Condition.PARTLY)) {
            stars.forEach { s ->
                val tw = 0.55f + 0.45f * sin(t * (1.5f + s.speed * 2f) + s.phase)
                drawStar(Offset(s.x * size.width, s.y * ground * 0.7f), m * 0.012f * (0.6f + s.size) * tw, Color(0xFFFFF59D))
            }
        }

        // Clouds drift with the wind.
        val cloudCount = when (condition) {
            Condition.CLEAR -> 1
            Condition.PARTLY -> 3
            Condition.FOG -> 2
            Condition.DRIZZLE, Condition.SNOW -> 5
            Condition.CLOUDY, Condition.RAIN, Condition.SLEET -> 6
            Condition.STORM -> 7
        }
        val stormy = condition in setOf(Condition.RAIN, Condition.STORM, Condition.SLEET)
        val cloudColor = when {
            condition == Condition.STORM -> skin.stormCloud
            stormy -> lerp(skin.cloud, skin.stormCloud, 0.55f)
            else -> skin.cloud
        }
        val drift = 8f + wind * 1.2f
        for (i in 0 until cloudCount) {
            val p = clouds[i]
            val w = m * (0.32f + p.size * 0.3f) * if (compact) 0.9f else 1f
            val span = size.width + w * 2
            val x = ((p.x * span + t * drift * (0.6f + p.speed)) % span) - w
            val y = size.height * (if (compact) 0.12f else 0.06f) + p.y * size.height * (if (compact) 0.4f else 0.3f)
            drawCloud(Offset(x, y), w, if (i % 2 == 0) cloudColor else lerp(cloudColor, Color.White, 0.15f))
        }

        // Precipitation.
        val slant = wind * 0.012f
        when (condition) {
            Condition.RAIN, Condition.STORM, Condition.DRIZZLE, Condition.SLEET -> {
                val count = if (condition == Condition.DRIZZLE) rain.size / 3 else rain.size
                val speed = if (condition == Condition.DRIZZLE) 0.55f else 1.25f
                val len = m * if (condition == Condition.DRIZZLE) 0.025f else 0.06f
                val drop = Color(0xFFB3F5FF)
                for (i in 0 until count) {
                    val p = rain[i]
                    val fall = size.height * (speed + p.speed * 0.6f)
                    val y = (p.y * size.height + t * fall) % (size.height + len) - len
                    val x = (p.x * (size.width + 200f) + y * slant) % (size.width + 100f) - 50f
                    if (condition == Condition.SLEET && i % 3 == 0) {
                        drawCircle(Color.White, m * 0.008f, Offset(x, y))
                    } else {
                        drawLine(drop, Offset(x, y), Offset(x + len * slant, y + len), strokeWidth = m * 0.008f, cap = StrokeCap.Round)
                    }
                }
            }
            Condition.SNOW -> flakes.forEach { p ->
                val fall = size.height * (0.12f + p.speed * 0.12f)
                val y = (p.y * size.height + t * fall) % (size.height + 20f) - 10f
                val x = p.x * size.width + sin(t * (0.8f + p.speed) + p.phase) * m * 0.04f + y * slant * 0.5f
                drawCircle(Color.White, m * (0.007f + p.size * 0.011f), Offset(x, y))
            }
            else -> Unit
        }

        // Lightning: two quick flashes and a bolt every few seconds.
        if (condition == Condition.STORM) {
            val period = 5.3f
            val cycle = (t / period).toInt()
            val local = t % period
            val flash = when {
                local < 0.09f -> 0.7f
                local in 0.18f..0.27f -> 0.45f
                else -> 0f
            }
            if (flash > 0f) {
                drawBolt(cycle, ground)
                drawRect(Color.White.copy(alpha = flash * 0.55f))
            }
        }

        // Fog bands.
        if (condition == Condition.FOG) {
            for (i in 0 until 5) {
                val y = size.height * (0.35f + i * 0.11f)
                val x = sin(t * 0.25f + i) * size.width * 0.08f
                drawRoundRect(
                    lerp(skin.cloud, top, 0.15f).copy(alpha = 0.82f),
                    topLeft = Offset(-size.width * 0.1f + x + (i % 2) * size.width * 0.15f, y),
                    size = Size(size.width * 0.95f, m * 0.055f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(m * 0.03f),
                )
            }
        }

        drawGround(ground, condition, isDay, skin, t, wind, compact)
    }
}

private fun particles(seed: Int, n: Int): List<Particle> {
    val r = Random(seed)
    return List(n) { Particle(r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat(), r.nextFloat() * 2f * PI.toFloat()) }
}

private fun DrawScope.drawSun(c: Offset, r: Float, t: Float, skin: Skin) {
    val outline = Stroke(r * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    rotate(t * 12f, c) {
        for (i in 0 until 12) {
            val a = (i * 30f) * (PI.toFloat() / 180f)
            val pulse = 1f + 0.08f * sin(t * 2.4f + i)
            val tip = Offset(c.x + cos(a) * r * 1.65f * pulse, c.y + sin(a) * r * 1.65f * pulse)
            val l = Offset(c.x + cos(a - 0.17f) * r * 1.08f, c.y + sin(a - 0.17f) * r * 1.08f)
            val rr = Offset(c.x + cos(a + 0.17f) * r * 1.08f, c.y + sin(a + 0.17f) * r * 1.08f)
            val ray = Path().apply { moveTo(l.x, l.y); lineTo(tip.x, tip.y); lineTo(rr.x, rr.y); close() }
            drawPath(ray, if (i % 2 == 0) skin.sun else lerp(skin.sun, Color(0xFFFF7A00), 0.5f))
            drawPath(ray, Ink, style = Stroke(r * 0.06f, join = StrokeJoin.Round))
        }
    }
    drawCircle(skin.sun, r, c)
    drawCircle(Ink, r, c, style = outline)
    // A friendly face. Blinks now and then.
    val blink = if ((t % 4.1f) < 0.14f) 0.15f else 1f
    val eyeR = r * 0.1f
    for (dx in listOf(-0.32f, 0.32f)) {
        scale(1f, blink, Offset(c.x + dx * r, c.y - r * 0.12f)) {
            drawCircle(Ink, eyeR, Offset(c.x + dx * r, c.y - r * 0.12f))
        }
        drawCircle(Color(0xFFFF6FB5), r * 0.13f, Offset(c.x + dx * r * 1.45f, c.y + r * 0.22f))
    }
    val smile = Path().apply {
        moveTo(c.x - r * 0.33f, c.y + r * 0.2f)
        quadraticTo(c.x, c.y + r * 0.55f, c.x + r * 0.33f, c.y + r * 0.2f)
    }
    drawPath(smile, Ink, style = Stroke(r * 0.08f, cap = StrokeCap.Round))
}

private fun DrawScope.drawMoon(c: Offset, r: Float) {
    val full = Path().apply { addOval(Rect(c, r)) }
    val bite = Path().apply { addOval(Rect(Offset(c.x + r * 0.55f, c.y - r * 0.3f), r * 0.95f)) }
    val crescent = Path.combine(PathOperation.Difference, full, bite)
    drawPath(crescent, Color(0xFFFFF176))
    drawPath(crescent, Ink, style = Stroke(r * 0.08f, join = StrokeJoin.Round))
    drawCircle(Ink, r * 0.07f, Offset(c.x - r * 0.45f, c.y - r * 0.05f))
}

private fun DrawScope.drawStar(c: Offset, r: Float, color: Color) {
    if (r <= 0.5f) return
    val p = Path().apply {
        moveTo(c.x, c.y - r * 2f)
        quadraticTo(c.x, c.y, c.x + r * 2f, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r * 2f)
        quadraticTo(c.x, c.y, c.x - r * 2f, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r * 2f)
        close()
    }
    drawPath(p, color)
}

/** A puffy toy cloud with an ink outline; [o] is its left-centre, [w] its width. */
fun DrawScope.drawCloud(o: Offset, w: Float, color: Color) {
    val h = w * 0.5f
    val body = listOf(
        Rect(Offset(o.x + w * 0.28f, o.y + h * 0.55f), w * 0.22f),
        Rect(Offset(o.x + w * 0.5f, o.y + h * 0.35f), w * 0.27f),
        Rect(Offset(o.x + w * 0.72f, o.y + h * 0.55f), w * 0.2f),
    ).map { Path().apply { addOval(it) } }.reduce { a, b -> Path.combine(PathOperation.Union, a, b) }
    val base = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(o.x + w * 0.08f, o.y + h * 0.55f, o.x + w * 0.92f, o.y + h * 1.05f, h * 0.25f, h * 0.25f))
    }
    val cloud = Path.combine(PathOperation.Union, body, base)
    drawPath(cloud, color)
    drawPath(cloud, Ink, style = Stroke(w * 0.022f, join = StrokeJoin.Round))
    drawCircle(Color.White.copy(alpha = 0.7f), w * 0.05f, Offset(o.x + w * 0.42f, o.y + h * 0.2f))
}

private fun DrawScope.drawBolt(cycle: Int, ground: Float) {
    val r = Random(cycle * 31 + 7)
    var x = size.width * (0.2f + r.nextFloat() * 0.6f)
    var y = size.height * 0.22f
    val bolt = Path().apply { moveTo(x, y) }
    val step = (ground - y) / 6f
    for (i in 0 until 6) {
        x += (r.nextFloat() - 0.5f) * size.width * 0.12f
        y += step
        bolt.lineTo(x, y)
    }
    val m = min(size.width, size.height)
    drawPath(bolt, Ink, style = Stroke(m * 0.03f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(bolt, Color(0xFFFFEA00), style = Stroke(m * 0.016f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawGround(
    ground: Float,
    condition: Condition,
    isDay: Boolean,
    skin: Skin,
    t: Float,
    wind: Float,
    compact: Boolean,
) {
    val w = size.width
    val h = size.height
    val m = min(w, h)
    val snowy = condition == Condition.SNOW || condition == Condition.SLEET
    val nightShade = if (isDay) 0f else 0.35f
    // Snow stays bright: icy white by day, tinted with the skin's night colour (never grey) after dark.
    val far = if (snowy) lerp(Color.White, skin.nightBottom, nightShade * 0.6f)
    else lerp(skin.hillFar, skin.nightTop, nightShade)
    val near = if (snowy) lerp(Color(0xFFE0FFFF), skin.nightBottom, nightShade * 0.45f)
    else lerp(skin.hillNear, skin.nightTop, nightShade * 0.8f)
    val outline = Stroke(m * 0.012f, join = StrokeJoin.Round)

    val farHill = Path().apply {
        moveTo(0f, ground - h * 0.05f)
        quadraticTo(w * 0.22f, ground - h * 0.13f, w * 0.48f, ground - h * 0.04f)
        quadraticTo(w * 0.75f, ground - h * 0.12f, w, ground - h * 0.06f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(farHill, far)
    drawPath(farHill, Ink, style = outline)

    // A little house on the far hill, lights on at night.
    if (!compact) {
        val hx = w * 0.18f
        val hy = ground - h * 0.1f
        val hs = m * 0.09f
        drawRect(skin.accent, Offset(hx, hy - hs), Size(hs, hs))
        drawRect(Ink, Offset(hx, hy - hs), Size(hs, hs), style = outline)
        val roof = Path().apply {
            moveTo(hx - hs * 0.18f, hy - hs); lineTo(hx + hs / 2f, hy - hs * 1.7f); lineTo(hx + hs * 1.18f, hy - hs); close()
        }
        drawPath(roof, Color(0xFFFF2E4D))
        drawPath(roof, Ink, style = outline)
        drawRect(if (isDay) Color(0xFF7DF9FF) else Color(0xFFFFEA00), Offset(hx + hs * 0.2f, hy - hs * 0.75f), Size(hs * 0.3f, hs * 0.3f))
        drawRect(Ink, Offset(hx + hs * 0.6f, hy - hs * 0.5f), Size(hs * 0.25f, hs * 0.5f))
    }

    val nearHill = Path().apply {
        moveTo(0f, ground + h * 0.02f)
        quadraticTo(w * 0.35f, ground - h * 0.07f, w * 0.62f, ground + h * 0.01f)
        quadraticTo(w * 0.85f, ground + h * 0.06f, w, ground - h * 0.02f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(nearHill, near)
    drawPath(nearHill, Ink, style = outline)

    // Lollipop tree that sways with the wind.
    val tx = w * if (compact) 0.88f else 0.8f
    val ty = ground + h * 0.03f
    val th = m * if (compact) 0.22f else 0.16f
    val sway = sin(t * (1.2f + wind / 25f)) * (2f + wind * 0.25f)
    rotate(sway, Offset(tx, ty)) {
        drawLine(Color(0xFF8D4B1F), Offset(tx, ty), Offset(tx, ty - th), strokeWidth = m * 0.025f, cap = StrokeCap.Round)
        val canopy = Offset(tx, ty - th)
        drawCircle(if (snowy) Color.White else lerp(skin.hillFar, Color(0xFF00C853), 0.3f), th * 0.42f, canopy)
        drawCircle(Ink, th * 0.42f, canopy, style = outline)
        drawCircle(Color.White.copy(alpha = 0.5f), th * 0.09f, Offset(canopy.x - th * 0.15f, canopy.y - th * 0.15f))
    }
    // Flowers dot the near hill on nice days.
    if (!snowy && isDay && condition in setOf(Condition.CLEAR, Condition.PARTLY)) {
        val r = Random(5)
        repeat(if (compact) 5 else 9) {
            val fx = w * (0.05f + r.nextFloat() * 0.6f)
            val fy = ground + h * (0.04f + r.nextFloat() * 0.1f)
            val bob = sin(t * 2f + it) * m * 0.004f
            drawCircle(listOf(Color(0xFFFF2BB4), Color(0xFFFFD600), Color.White)[it % 3], m * 0.012f, Offset(fx, fy + bob))
        }
    }
}
