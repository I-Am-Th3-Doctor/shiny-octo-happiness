package io.github.iamth3doctor.searchlight.weatherapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import io.github.iamth3doctor.searchlight.weather.SceneConditions
import io.github.iamth3doctor.searchlight.weather.ScenePhysics
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Draws the living weather scene: a sky lit by where the sun really is, the sun or the moon (its
 * true phase), stars, procedural clouds (as many and as dense as the cloud cover; every one a new
 * shape, drifting with the wind), rain as falling particles (denser and faster the harder it
 * rains, slanted by the wind), snow, lightning, fog, and hills on the horizon. Every number comes
 * from [SceneConditions]; changes (a new reading, scrubbing the day) ease in rather than jump.
 * The small things that make it alive: birds crossing a fair sky, drops on the glass while it
 * rains, gusts that sway the rain and hurry the clouds.
 *
 * Shared by the full-screen weather view and the home-screen widget ([mini]: fewer particles and
 * bigger features for a small window). Not thread-safe: step and draw on the UI thread.
 */
class SceneRenderer(private val density: Float, private val mini: Boolean) {

    /** Lightning struck (the screen plays thunder and a haptic). */
    var onStrike: (() -> Unit)? = null

    private val rnd = Random(System.nanoTime())
    private var w = 0
    private var h = 0
    private var time = 0.0

    // ---- the eased state (what's drawn) and where it's heading
    private var target: SceneConditions? = null
    private var cover = 0.0
    private var rain = 0.0
    private var snow = 0.0
    private var fog = 0.0
    private var storm = 0.0
    private var windEast = 0.0
    private var sunAlt = 30.0
    private var sunAz = 0.0
    private var moonAlt = -30.0
    private var moonAz = 0.0
    private var moonAge = 0.5
    private var hasState = false

    // ---- read by tests
    var frames = 0L
        private set
    var strikes = 0
        private set
    /** Clouds in the sky now (not counting ones fading away). */
    val cloudCount: Int get() = clouds.count { it.fade >= 0 }
    val dropCount: Int get() = drops
    val flakeCount: Int get() = flakes
    var skyTop = 0
        private set
    var skyBottom = 0
        private set
    /** Birds in the sky now. */
    val birdCount: Int get() = birds.size
    /** Raindrops on the glass now (full screen only). */
    val glassDropCount: Int get() = glass.size
    /** Whether a lightning flash is lit right now. */
    val flashing: Boolean get() = flash > 0.05

    /**
     * How quickly the drawn scene catches up with a change (seconds): a new reading eases in; a
     * time-lapse, where an hour passes in a third of a second, keeps up close behind.
     */
    var easeSec = 0.7

    fun setTarget(c: SceneConditions, immediate: Boolean) {
        target = c
        if (immediate || !hasState) {
            cover = c.cloudCover; rain = c.rain; snow = c.snow; fog = c.fog; storm = if (c.thunder || c.severe != null) 1.0 else 0.0
            windEast = c.windEastMph; sunAlt = c.sunAltDeg; sunAz = c.sunAz; moonAlt = c.moonAltDeg; moonAz = c.moonAz
            moonAge = c.moonAge
            hasState = true
            drops = 0 // refill straight away, at the new rate
            flakes = 0
            if (w > 0) seedClouds()
        }
    }

    fun resize(width: Int, height: Int) {
        if (width == w && height == h) return
        w = width
        h = height
        if (w <= 0 || h <= 0) return
        buildStars()
        buildHills()
        skyShader = null
        seedClouds()
        drops = 0
        flakes = 0
    }

    private val wDp get() = w / density
    private val hDp get() = h / density

    // ------------------------------------------------------------------ stepping

    fun step(dtSec: Double) {
        val c = target ?: return
        if (w <= 0) return
        frames++
        val dt = dtSec.coerceIn(0.0, 0.1)
        time += dt
        val k = 1 - exp(-dt / easeSec)
        cover += (c.cloudCover - cover) * k
        rain += (c.rain - rain) * k
        snow += (c.snow - snow) * k
        fog += (c.fog - fog) * k
        storm += ((if (c.thunder || c.severe != null) 1.0 else 0.0) - storm) * k
        windEast += (c.windEastMph - windEast) * k
        sunAlt += (c.sunAltDeg - sunAlt) * k
        sunAz += (c.sunAz - sunAz) * k
        moonAlt += (c.moonAltDeg - moonAlt) * k
        moonAz += (c.moonAz - moonAz) * k
        moonAge = c.moonAge

        stepGust()
        stepClouds(dt)
        stepBirds(dt)
        stepGlass(dt)
        stepRain(dt)
        stepSnow(dt)
        stepLightning(dt, c.thunder)
    }

    // ------------------------------------------------------------------ wind gusts

    /** 1 in still air; in a wind, swings above and below as gusts come and go. */
    private var gust = 1.0

    private fun stepGust() {
        val amount = (abs(windEast) / 35).coerceIn(0.0, 0.5)
        gust = 1 + amount * (0.6 * sin(time * 0.9) + 0.4 * sin(time * 0.37 + 1.7))
    }

    // ------------------------------------------------------------------ clouds

    private class Cloud(
        var x: Float,
        var y: Float,
        val puffs: FloatArray, // dx, dy, r triples, px relative to (x, y)
        val halfWidth: Float,
        val speed: Float,
        var alpha: Float,
        /** +1 fading in, -1 fading out (then gone), 0 settled. */
        var fade: Int,
        val tone: Float,
    )

    private val clouds = ArrayList<Cloud>()

    /** A new cloud: 5-10 soft puffs, flat underneath; no two alike. */
    private fun newCloud(x: Float, fadeIn: Boolean): Cloud {
        val scale = if (mini) 1.5f else 1f
        val width = (w * (0.16f + rnd.nextFloat() * 0.26f) * scale).coerceAtMost(w * 0.7f)
        val n = 5 + rnd.nextInt(6)
        val puffs = FloatArray(n * 3)
        for (i in 0 until n) {
            val t = if (n == 1) 0.5f else i / (n - 1f)
            val dx = (t - 0.5f) * width * 0.85f + (rnd.nextFloat() - 0.5f) * width * 0.12f
            val edge = 1f - abs(t - 0.5f) * 1.2f
            val r = width * (0.12f + rnd.nextFloat() * 0.14f) * (0.55f + edge * 0.6f)
            puffs[i * 3] = dx
            puffs[i * 3 + 1] = -r * (0.25f + rnd.nextFloat() * 0.35f)
            puffs[i * 3 + 2] = r
        }
        val band = if (mini) 0.5f else 0.42f
        val y = h * (0.1f + rnd.nextFloat() * band)
        return Cloud(x, y, puffs, width / 2 + width * 0.15f, 0.6f + rnd.nextFloat() * 0.8f,
            if (fadeIn) 0f else 1f, if (fadeIn) 1 else 0, rnd.nextFloat())
    }

    private fun seedClouds() {
        clouds.clear()
        val n = ScenePhysics.cloudCount(cover, wDp)
        repeat(n) { clouds += newCloud(rnd.nextFloat() * w * 1.2f - w * 0.1f, fadeIn = false) }
    }

    private fun stepClouds(dt: Double) {
        val want = ScenePhysics.cloudCount(cover, wDp)
        val alive = clouds.count { it.fade >= 0 }
        if (alive < want) {
            // More cover: new clouds gather (fading in where they form).
            repeat(want - alive) { clouds += newCloud(rnd.nextFloat() * w, fadeIn = true) }
        } else if (alive > want) {
            clouds.filter { it.fade >= 0 }.shuffled(rnd).take(alive - want).forEach { it.fade = -1 }
        }
        val v = ScenePhysics.cloudSpeed(windEast) * density * (if (mini) 0.6 else 1.0) * (0.8 + 0.2 * gust)
        val it = clouds.iterator()
        while (it.hasNext()) {
            val c = it.next()
            c.x += (v * c.speed * dt).toFloat()
            when (c.fade) {
                1 -> { c.alpha = (c.alpha + dt.toFloat() / 1.6f).coerceAtMost(1f); if (c.alpha >= 1f) c.fade = 0 }
                -1 -> { c.alpha -= dt.toFloat() / 1.6f; if (c.alpha <= 0f) { it.remove(); continue } }
            }
            val gone = if (v >= 0) c.x - c.halfWidth > w else c.x + c.halfWidth < 0
            if (gone) {
                // Blown off one side: a brand-new cloud comes in from the other.
                it.remove()
                if (c.fade >= 0) pendingRespawn++
            }
        }
        while (pendingRespawn > 0) {
            pendingRespawn--
            val fresh = newCloud(0f, fadeIn = false)
            fresh.x = if (v >= 0) -fresh.halfWidth else w + fresh.halfWidth
            clouds += fresh
        }
    }

    private var pendingRespawn = 0

    // ------------------------------------------------------------------ rain and snow

    private val dropCap = if (mini) 260 else 1100
    private val rx = FloatArray(dropCap)
    private val ry = FloatArray(dropCap)
    private val rs = FloatArray(dropCap)
    private var drops = 0
    private val lines = FloatArray(dropCap * 4)

    private fun rainVelocity(): Pair<Float, Float> {
        val vy = (ScenePhysics.fallSpeed(rain) * density * (if (mini) 0.7 else 1.0)).toFloat()
        val vx = (ScenePhysics.drift(windEast) * gust * density * (if (mini) 0.7 else 1.0)).toFloat()
        return vx to vy
    }

    /** Where a drop starts so it can slant across the whole view: upwind of the screen by the slant. */
    private fun dropX(vx: Float, vy: Float): Float {
        val lean = vx / vy * h
        return rnd.nextFloat() * (w + abs(lean)) - (if (lean > 0) lean else 0f)
    }

    private fun stepRain(dt: Double) {
        val want = ScenePhysics.drops(rain, wDp, hDp, dropCap).let { if (rain < 0.03) 0 else it }
        val (vx, vy) = rainVelocity()
        while (drops < want) {
            rx[drops] = dropX(vx, vy)
            ry[drops] = rnd.nextFloat() * h
            rs[drops] = 0.8f + rnd.nextFloat() * 0.45f
            drops++
        }
        if (drops > want) drops = max(want, drops - (dropCap * dt).toInt().coerceAtLeast(1))
        for (i in 0 until drops) {
            ry[i] += vy * rs[i] * dt.toFloat()
            rx[i] += vx * rs[i] * dt.toFloat()
            if (ry[i] > h) {
                ry[i] = -rnd.nextFloat() * h * 0.15f
                rx[i] = dropX(vx, vy)
            }
        }
    }

    private val flakeCap = if (mini) 120 else 450
    private val sx = FloatArray(flakeCap)
    private val sy = FloatArray(flakeCap)
    private val sp = FloatArray(flakeCap)
    private var flakes = 0
    private val points = FloatArray(flakeCap * 2)

    private fun stepSnow(dt: Double) {
        val want = ScenePhysics.flakes(snow, wDp, hDp, flakeCap).let { if (snow < 0.03) 0 else it }
        while (flakes < want) {
            sx[flakes] = rnd.nextFloat() * w
            sy[flakes] = rnd.nextFloat() * h
            sp[flakes] = rnd.nextFloat() * 6.28f
            flakes++
        }
        if (flakes > want) flakes = max(want, flakes - (flakeCap * dt).toInt().coerceAtLeast(1))
        val fall = (45 + 45 * snow) * density
        val drift = ScenePhysics.drift(windEast) * 0.35 * density
        for (i in 0 until flakes) {
            val f = 0.6f + (i % 5) * 0.12f
            sy[i] += (fall * f * dt).toFloat()
            sx[i] += ((drift + sin(time * 1.3 + sp[i]) * 16 * density) * dt).toFloat()
            if (sy[i] > h) { sy[i] = -4f; sx[i] = rnd.nextFloat() * w }
            if (sx[i] < -8) sx[i] += w + 16 else if (sx[i] > w + 8) sx[i] -= w + 16
        }
    }

    // ------------------------------------------------------------------ birds

    private class Bird(var x: Float, var y: Float, val vx: Float, val size: Float, val phase: Float, var flap: Float)

    private val birds = ArrayList<Bird>()
    private var nextFlock = 0.6

    /** Fair weather in daylight: a calm enough sky for birds. */
    private fun birdWeather() = darkness < 0.35 && rain < 0.05 && snow < 0.05 && storm < 0.1 && cover < 0.65 && fog < 0.3

    private fun stepBirds(dt: Double) {
        val dir = if (windEast < -3) -1f else 1f
        if (birdWeather()) {
            nextFlock -= dt
            if (nextFlock <= 0 && birds.size < 8) {
                // A small flock: three to five, loosely together, coming in from upwind.
                val n = 3 + rnd.nextInt(3)
                val y0 = h * (0.12f + rnd.nextFloat() * 0.25f)
                val speed = (28 + rnd.nextFloat() * 18) * density * (if (mini) 0.6f else 1f) * dir
                val size = (if (mini) 3.2f else 5.5f) * density
                for (i in 0 until n) {
                    val x0 = if (dir > 0) -size * 4 - i * size * 5 else w + size * 4 + i * size * 5
                    birds += Bird(x0, y0 + (rnd.nextFloat() - 0.5f) * size * 6, speed * (0.9f + rnd.nextFloat() * 0.2f),
                        size * (0.8f + rnd.nextFloat() * 0.4f), rnd.nextFloat() * 6.28f, 0f)
                }
                nextFlock = 7.0 + rnd.nextDouble() * 9
            }
        } else {
            nextFlock = 0.6
        }
        val it = birds.iterator()
        while (it.hasNext()) {
            val b = it.next()
            b.x += (b.vx * dt).toFloat()
            b.y += (sin(time * 0.8 + b.phase) * 4 * density * dt).toFloat()
            b.flap = sin(time * 7.5 + b.phase).toFloat()
            if (b.x < -w * 0.3f || b.x > w * 1.3f) it.remove()
        }
    }

    private val birdPath = Path()

    private fun drawBirds(canvas: Canvas) {
        if (birds.isEmpty()) return
        val c = mix(0xFF2A2F3A.toInt(), 0xFF101318.toInt(), darkness)
        boltPaint.color = c
        boltPaint.strokeWidth = (if (mini) 1.1f else 1.6f) * density
        for (b in birds) {
            val lift = b.size * (0.15f + 0.55f * b.flap)
            birdPath.reset()
            birdPath.moveTo(b.x - b.size, b.y - lift)
            birdPath.quadTo(b.x - b.size * 0.45f, b.y - lift * 0.2f - b.size * 0.15f, b.x, b.y)
            birdPath.quadTo(b.x + b.size * 0.45f, b.y - lift * 0.2f - b.size * 0.15f, b.x + b.size, b.y - lift)
            canvas.drawPath(birdPath, boltPaint)
        }
    }

    // ------------------------------------------------------------------ drops on the glass

    private class GlassDrop(var x: Float, var y: Float, val r: Float, var vy: Float, var alpha: Float, var fading: Boolean = false)

    private val glass = ArrayList<GlassDrop>()

    private fun stepGlass(dt: Double) {
        if (mini) return
        val want = if (rain < 0.08) 0 else (8 + rain * 34).toInt()
        val d = dt.toFloat()
        // While it rains, drops land on the glass; when it stops, they dry away.
        if (glass.count { !it.fading } < want && rnd.nextFloat() < d * (2 + rain.toFloat() * 10)) {
            glass += GlassDrop(rnd.nextFloat() * w, rnd.nextFloat() * h * 0.9f, (2.5f + rnd.nextFloat() * 5.5f) * density, 0f, 0f)
        }
        var extra = glass.count { !it.fading } - want
        val it = glass.iterator()
        while (it.hasNext()) {
            val g = it.next()
            if (extra > 0 && !g.fading) { g.fading = true; extra-- }
            g.alpha = if (g.fading) g.alpha - d / 2.5f else (g.alpha + d / 0.6f).coerceAtMost(1f)
            // Big drops give way now and then and run down the glass.
            if (g.r > 5 * density && g.vy == 0f && rnd.nextFloat() < d * 0.08f) g.vy = (30 + rnd.nextFloat() * 50) * density
            if (g.vy > 0f) {
                g.vy *= (1 - d * 0.6f)
                if (g.vy < 4 * density) g.vy = 0f
            }
            g.y += g.vy * d
            if (g.alpha <= 0f || g.y > h + g.r) it.remove()
        }
    }

    private fun drawGlass(canvas: Canvas) {
        if (glass.isEmpty()) return
        paint.style = Paint.Style.FILL
        for (g in glass) {
            val a = g.alpha.coerceIn(0f, 1f).toDouble()
            paint.color = withAlpha(mix(skyBottom, Color.WHITE, 0.35), 0.28 * a)
            canvas.drawCircle(g.x, g.y, g.r, paint)
            paint.color = withAlpha(0xFF0A0E14.toInt(), 0.22 * a)
            canvas.drawCircle(g.x + g.r * 0.15f, g.y + g.r * 0.25f, g.r * 0.85f, paint)
            paint.color = withAlpha(Color.WHITE, 0.7 * a)
            canvas.drawCircle(g.x - g.r * 0.35f, g.y - g.r * 0.35f, g.r * 0.28f, paint)
        }
    }

    // ------------------------------------------------------------------ lightning

    private var nextStrike = 1.5
    private var flash = 0.0
    private var boltLife = 0.0
    private val bolt = Path()

    private fun stepLightning(dt: Double, thunder: Boolean) {
        flash = max(0.0, flash - dt / 0.45)
        boltLife = max(0.0, boltLife - dt / 0.3)
        if (!thunder) { nextStrike = 1.0 + rnd.nextDouble() * 2; return }
        nextStrike -= dt
        if (nextStrike > 0) return
        nextStrike = (if (mini) 5.0 else 3.0) + rnd.nextDouble() * 6
        flash = 1.0
        boltLife = 1.0
        strikes++
        bolt.reset()
        var x = w * (0.2f + rnd.nextFloat() * 0.6f)
        var y = h * 0.14f
        bolt.moveTo(x, y)
        val bottom = h * (0.55f + rnd.nextFloat() * 0.2f)
        while (y < bottom) {
            y += h * (0.04f + rnd.nextFloat() * 0.05f)
            x += w * (rnd.nextFloat() - 0.5f) * 0.09f
            bolt.lineTo(x, y)
        }
        onStrike?.invoke()
    }

    // ------------------------------------------------------------------ stars and hills

    private var stars = FloatArray(0)

    private fun buildStars() {
        val n = if (mini) 24 else 80
        stars = FloatArray(n * 4)
        for (i in 0 until n) {
            stars[i * 4] = rnd.nextFloat() * w
            stars[i * 4 + 1] = rnd.nextFloat() * h * 0.62f
            stars[i * 4 + 2] = (0.6f + rnd.nextFloat() * 1.1f) * density
            stars[i * 4 + 3] = rnd.nextFloat() * 6.28f
        }
    }

    private val farHills = Path()
    private val nearHills = Path()

    private fun buildHills() {
        fun ridge(p: Path, base: Float, amp: Float, bumps: Int) {
            p.reset()
            p.moveTo(0f, h.toFloat())
            val phase = rnd.nextFloat() * 6.28f
            val phase2 = rnd.nextFloat() * 6.28f
            val steps = 48
            for (i in 0..steps) {
                val x = w * i / steps.toFloat()
                val t = i / steps.toFloat()
                val y = base - amp * (0.55f * sin(t * bumps * PI.toFloat() + phase) + 0.45f * sin(t * bumps * 2.3f + phase2))
                p.lineTo(x, y)
            }
            p.lineTo(w.toFloat(), h.toFloat())
            p.close()
        }
        ridge(farHills, h * 0.86f, h * 0.035f, 3)
        ridge(nearHills, h * 0.93f, h * 0.03f, 2)
    }

    // ------------------------------------------------------------------ drawing

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rainPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val snowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private var skyShader: Shader? = null
    private var shaderTop = 0
    private var shaderBottom = 0
    private val rect = RectF()

    /** A soft round puff (white, alpha falling off to the edge): clouds are built from it. */
    private val puff: Bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(48f, 48f, 48f, intArrayOf(Color.WHITE, Color.argb(235, 255, 255, 255), Color.argb(120, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 0.78f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(48f, 48f, 48f, p)
    }

    /** A warm glow (sun) or cool glow (moon), tinted when drawn. */
    private val glow: Bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also { b ->
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = RadialGradient(64f, 64f, 64f, intArrayOf(Color.argb(200, 255, 255, 255), Color.argb(60, 255, 255, 255), Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(64f, 64f, 64f, p)
    }

    private val darkness get() = ((4 - sunAlt) / 16).coerceIn(0.0, 1.0)

    fun draw(canvas: Canvas) {
        if (w <= 0 || h <= 0 || target == null) return
        drawSky(canvas)
        drawStars(canvas)
        drawMoon(canvas)
        drawSun(canvas)
        drawBirds(canvas)
        drawClouds(canvas)
        drawOvercast(canvas)
        if (boltLife > 0) {
            boltPaint.color = Color.argb((255 * boltLife).toInt(), 255, 250, 225)
            boltPaint.strokeWidth = (if (mini) 1.6f else 2.6f) * density
            canvas.drawPath(bolt, boltPaint)
        }
        drawHills(canvas)
        drawRain(canvas)
        drawSnow(canvas)
        drawFog(canvas)
        drawGlass(canvas)
        if (flash > 0) canvas.drawColor(Color.argb((110 * flash * flash).toInt(), 235, 238, 255))
    }

    // Sky colors by the sun's altitude (degrees), night to full day.
    private val stopsAlt = doubleArrayOf(-18.0, -10.0, -4.0, 0.0, 6.0, 15.0)
    private val stopsTop = intArrayOf(0xFF050816.toInt(), 0xFF0B1230.toInt(), 0xFF1F2A5A.toInt(), 0xFF3C5A9A.toInt(), 0xFF4A7CC4.toInt(), 0xFF3A78D6.toInt())
    private val stopsBottom = intArrayOf(0xFF121A36.toInt(), 0xFF2B2A55.toInt(), 0xFFB0607A.toInt(), 0xFFF19A6A.toInt(), 0xFFF6C58A.toInt(), 0xFFA9D3F7.toInt())

    private fun byAltitude(stops: IntArray): Int {
        val a = sunAlt
        if (a <= stopsAlt.first()) return stops.first()
        if (a >= stopsAlt.last()) return stops.last()
        val i = stopsAlt.indexOfLast { it <= a }
        val t = (a - stopsAlt[i]) / (stopsAlt[i + 1] - stopsAlt[i])
        return mix(stops[i], stops[i + 1], t)
    }

    private fun drawSky(canvas: Canvas) {
        var top = byAltitude(stopsTop)
        var bottom = byAltitude(stopsBottom)
        val dark = darkness
        val grayTop = mix(0xFF7F8A96.toInt(), 0xFF12151B.toInt(), dark)
        val grayBottom = mix(0xFFB9C1CA.toInt(), 0xFF22272F.toInt(), dark)
        val overcast = (cover.pow(1.6) * 0.72 + rain * 0.2 + fog * 0.35).coerceIn(0.0, 0.92)
        top = mix(top, grayTop, overcast)
        bottom = mix(bottom, grayBottom, overcast)
        if (storm > 0) {
            top = mix(top, 0xFF1A1D26.toInt(), storm * 0.45)
            bottom = mix(bottom, 0xFF3A3F4A.toInt(), storm * 0.35)
        }
        skyTop = top
        skyBottom = bottom
        if (skyShader == null || top != shaderTop || bottom != shaderBottom) {
            skyShader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            shaderTop = top
            shaderBottom = bottom
        }
        paint.shader = skyShader
        paint.colorFilter = null
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
    }

    private fun drawStars(canvas: Canvas) {
        val vis = darkness * (1 - cover).pow(1.5) * (1 - fog)
        if (vis < 0.02) return
        paint.style = Paint.Style.FILL
        for (i in 0 until stars.size / 4) {
            val tw = 0.55 + 0.45 * sin(time * (0.8 + (i % 7) * 0.25) + stars[i * 4 + 3])
            paint.color = Color.argb((255 * vis * tw).toInt().coerceIn(0, 255), 255, 255, 255)
            canvas.drawCircle(stars[i * 4], stars[i * 4 + 1], stars[i * 4 + 2], paint)
        }
    }

    /** Sky position from altitude (degrees) and azimuth (radians from south): facing south, east on the left. */
    private fun skyX(az: Double) = (w * (0.5 + (az / PI) * 0.9)).toFloat().coerceIn(w * 0.08f, w * 0.92f)
    private fun skyY(alt: Double): Float {
        val horizon = h * 0.84f
        val top = h * (if (mini) 0.2f else 0.12f)
        return horizon - ((alt.coerceIn(-6.0, 60.0) / 60.0) * (horizon - top)).toFloat()
    }

    private fun drawSun(canvas: Canvas) {
        if (sunAlt < -4) return
        val x = skyX(sunAz)
        val y = skyY(sunAlt)
        val r = min(w, h) * (if (mini) 0.085f else 0.055f)
        val vis = (1 - cover * 0.82 - rain * 0.1).coerceIn(0.1, 1.0) * ((sunAlt + 4) / 6).coerceIn(0.0, 1.0)
        val low = ((8 - sunAlt) / 10).coerceIn(0.0, 1.0)
        val glowColor = mix(0xFFFFE9A8.toInt(), 0xFFFF9E57.toInt(), low)
        bmpPaint.colorFilter = PorterDuffColorFilter(glowColor, PorterDuff.Mode.SRC_IN)
        bmpPaint.alpha = (255 * vis).toInt()
        val g = r * 5
        rect.set(x - g, y - g, x + g, y + g)
        canvas.drawBitmap(glow, null, rect, bmpPaint)
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(mix(0xFFFFF4CC.toInt(), 0xFFFFC27A.toInt(), low), vis)
        canvas.drawCircle(x, y, r, paint)
    }

    private val moonPath = Path()
    private val oval = RectF()

    private fun drawMoon(canvas: Canvas) {
        if (moonAlt < -4) return
        val x = skyX(moonAz)
        val y = skyY(moonAlt)
        val r = min(w, h) * (if (mini) 0.07f else 0.045f)
        val night = darkness
        val vis = (1 - cover * 0.85).coerceIn(0.08, 1.0) * ((moonAlt + 4) / 6).coerceIn(0.0, 1.0) * (0.35 + 0.65 * night)
        if (night > 0.3) {
            bmpPaint.colorFilter = PorterDuffColorFilter(0xFFDCE4FF.toInt(), PorterDuff.Mode.SRC_IN)
            bmpPaint.alpha = (150 * vis * night).toInt()
            val g = r * 4
            rect.set(x - g, y - g, x + g, y + g)
            canvas.drawBitmap(glow, null, rect, bmpPaint)
        }
        // The dark side, faintly, then the lit part: a half disc and the terminator's half ellipse.
        paint.style = Paint.Style.FILL
        paint.color = withAlpha(0xFF2A3045.toInt(), vis * 0.55)
        canvas.drawCircle(x, y, r, paint)
        val waxing = moonAge < 0.5
        val k = cos(2 * PI * moonAge).toFloat() // 1 new, 0 quarter, -1 full
        moonPath.reset()
        rect.set(x - r, y - r, x + r, y + r)
        moonPath.arcTo(rect, -90f, 180f) // the lit limb: top, round the right, to the bottom
        val rx = abs(k) * r
        oval.set(x - rx, y - r, x + rx, y + r)
        // Crescent (k > 0): the terminator bows toward the limb; gibbous: away from it.
        if (k > 0) moonPath.arcTo(oval, 90f, -180f) else moonPath.arcTo(oval, 90f, 180f)
        moonPath.close()
        canvas.save()
        if (!waxing) canvas.scale(-1f, 1f, x, y) // waning: lit on the left (northern sky)
        paint.color = withAlpha(0xFFF4ECCB.toInt(), vis)
        canvas.drawPath(moonPath, paint)
        canvas.restore()
    }

    private fun cloudColor(): Int {
        var c = mix(0xFFF6F8FA.toInt(), 0xFF5E6670.toInt(), (rain * 0.75 + storm * 0.25 + (cover - 0.8).coerceAtLeast(0.0)).coerceIn(0.0, 0.85))
        c = mix(c, 0xFF2A303C.toInt(), darkness * 0.82)
        val dusk = (1 - abs(sunAlt - 1) / 9).coerceIn(0.0, 1.0)
        return mix(c, 0xFFF2A97E.toInt(), dusk * 0.28)
    }

    private fun drawClouds(canvas: Canvas) {
        if (clouds.isEmpty()) return
        val base = cloudColor()
        val shade = mix(base, Color.BLACK, 0.22)
        val thick = (0.75 + cover * 0.25)
        for (cl in clouds) {
            val a = (cl.alpha * thick).toFloat().coerceIn(0f, 1f)
            if (a <= 0.01f) continue
            // The shaded underside first, a little lower; then the cloud.
            for (pass in 0..1) {
                val color = if (pass == 0) shade else mix(base, Color.WHITE, (cl.tone * 0.08).toDouble())
                bmpPaint.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
                bmpPaint.alpha = (255 * a * (if (pass == 0) 0.8f else 1f)).toInt()
                val dy = if (pass == 0) cl.halfWidth * 0.06f else 0f
                var i = 0
                while (i < cl.puffs.size) {
                    val px = cl.x + cl.puffs[i]
                    val py = cl.y + cl.puffs[i + 1] + dy
                    val r = cl.puffs[i + 2] * 1.25f
                    rect.set(px - r, py - r, px + r, py + r)
                    canvas.drawBitmap(puff, null, rect, bmpPaint)
                    i += 3
                }
            }
        }
    }

    private var overcastShader: Shader? = null
    private var overcastColor = 0

    /** Heavy cover: a deck of cloud across the top, beyond the separate clouds. */
    private fun drawOvercast(canvas: Canvas) {
        val a = ((cover - 0.6) / 0.4).coerceIn(0.0, 1.0)
        if (a <= 0) return
        val c = withAlpha(cloudColor(), a * 0.9)
        if (overcastShader == null || c != overcastColor) {
            overcastShader = LinearGradient(0f, 0f, 0f, h * 0.55f, c, c and 0x00FFFFFF, Shader.TileMode.CLAMP)
            overcastColor = c
        }
        paint.shader = overcastShader
        canvas.drawRect(0f, 0f, w.toFloat(), h * 0.55f, paint)
        paint.shader = null
    }

    private fun drawHills(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = mix(skyBottom, 0xFF0E1218.toInt(), 0.55)
        canvas.drawPath(farHills, paint)
        paint.color = mix(skyBottom, 0xFF080A0E.toInt(), 0.75)
        canvas.drawPath(nearHills, paint)
    }

    private fun drawRain(canvas: Canvas) {
        if (drops == 0) return
        val (vx, vy) = rainVelocity()
        val len = 0.024f
        for (i in 0 until drops) {
            val j = i * 4
            lines[j] = rx[i]
            lines[j + 1] = ry[i]
            lines[j + 2] = rx[i] - vx * rs[i] * len
            lines[j + 3] = ry[i] - vy * rs[i] * len
        }
        rainPaint.strokeWidth = (if (mini) 0.9f else 1.2f) * density
        rainPaint.color = withAlpha(0xFFD6E6F5.toInt(), 0.42 + 0.2 * rain - darkness * 0.12)
        canvas.drawLines(lines, 0, drops * 4, rainPaint)
    }

    private fun drawSnow(canvas: Canvas) {
        if (flakes == 0) return
        for (i in 0 until flakes) { points[i * 2] = sx[i]; points[i * 2 + 1] = sy[i] }
        snowPaint.alpha = 230
        snowPaint.strokeWidth = (if (mini) 2.2f else 3.2f) * density
        canvas.drawPoints(points, 0, flakes * 2, snowPaint)
    }

    private fun drawFog(canvas: Canvas) {
        if (fog < 0.02) return
        val c = mix(0xFFD9DDE2.toInt(), 0xFF3A4048.toInt(), darkness)
        for (b in 0 until 3) {
            val cy = h * (0.55f + b * 0.14f) + (sin(time * 0.15 + b * 2.1) * h * 0.02).toFloat()
            val half = h * 0.12f
            val a = fog * (0.35 + 0.15 * sin(time * 0.2 + b))
            paint.shader = LinearGradient(0f, cy - half, 0f, cy + half,
                intArrayOf(c and 0x00FFFFFF, withAlpha(c, a), c and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, cy - half, w.toFloat(), cy + half, paint)
        }
        paint.shader = null
    }

    companion object {
        fun mix(a: Int, b: Int, t: Double): Int {
            val f = t.coerceIn(0.0, 1.0)
            fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * f).toInt()
            return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        fun withAlpha(c: Int, a: Double): Int = (c and 0x00FFFFFF) or ((255 * a.coerceIn(0.0, 1.0)).toInt() shl 24)
    }
}
