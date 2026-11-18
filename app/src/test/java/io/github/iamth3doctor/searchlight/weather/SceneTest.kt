package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SceneTest {

    private val hour = 3_600_000L
    // 2026-11-18 12:05 PST
    private val now = 1_795_550_400_000L + 5 * 60_000L

    private fun data(code: Int, cloud: Int?, precip: Double?, windFrom: Double = 200.0, wind: Double = 6.0): WeatherData {
        val h0 = now / hour * hour
        val hours = (-3..30).map { i ->
            HourPoint(h0 + i * hour, 10.0 + i * 0.5, if (i in 3..5) 63 else code, true,
                cloudPct = if (i in 3..5) 100 else cloud, precipMm = if (i in 3..5) 4.0 else precip,
                precipProb = if (i in 3..5) 80 else 5, windMph = wind + i, windFromDeg = windFrom)
        }
        val detail = WeatherDetail(-28800, 8.0, 80, 5.0, 1015.0, 20000.0, wind, windFrom, 1.0, hours, emptyList(), 30,
            cloudPct = cloud, precipMm = precip)
        return WeatherData("Tacoma", 47.25, -122.44, 11.0, code, true, 14.0, 4.0, now, detail)
    }

    @Test fun conditionsDifferByWeather() {
        val clear = SceneModel.at(data(0, 2, 0.0), now, live = true)
        val cloudy = SceneModel.at(data(3, 96, 0.0), now, live = true)
        val rain = SceneModel.at(data(63, 100, 3.0), now, live = true)
        val storm = SceneModel.at(data(95, 100, 6.0), now, live = true)
        assertTrue(clear.cloudCover < 0.1 && clear.rain == 0.0 && !clear.thunder)
        assertTrue(cloudy.cloudCover > 0.9 && cloudy.rain == 0.0)
        assertTrue(rain.rain > 0.4 && rain.cloudCover >= 0.9 && !rain.thunder)
        assertTrue(storm.thunder && storm.rain > rain.rain)
        val snow = SceneModel.at(data(73, 100, 1.0), now, live = true)
        assertTrue(snow.snow > 0 && snow.rain == 0.0)
        val fog = SceneModel.at(data(45, null, null), now, live = true)
        assertTrue(fog.fog > 0.5)
    }

    @Test fun sunnyWindyAndRainyAtOnce() {
        // A passing shower on a bright, windy day: some cloud, rain, and a strong wind, all at once.
        val c = SceneModel.at(data(80, 45, 1.2, windFrom = 270.0, wind = 25.0), now, live = true)
        assertTrue(c.rain > 0)
        assertTrue(c.windMph >= 25)
        assertTrue("blowing east", c.windEastMph > 20)
        assertTrue(c.sunAltDeg > 10) // midday in Tacoma in November: the sun's up
    }

    @Test fun moreRainMeansMoreAndFasterDrops() {
        val light = SceneModel.intensity(61, 0.3)
        val heavy = SceneModel.intensity(65, 9.0)
        assertTrue(heavy > light)
        assertTrue(ScenePhysics.drops(heavy, 400f, 800f, 2000) > ScenePhysics.drops(light, 400f, 800f, 2000))
        assertTrue(ScenePhysics.fallSpeed(heavy) > ScenePhysics.fallSpeed(light))
        assertEquals(0, ScenePhysics.drops(0.0, 400f, 800f, 2000))
    }

    @Test fun moreCoverMeansMoreClouds() {
        assertEquals(0, ScenePhysics.cloudCount(0.02, 400f))
        val few = ScenePhysics.cloudCount(0.25, 400f)
        val lots = ScenePhysics.cloudCount(0.95, 400f)
        assertTrue("$few < $lots", few in 1 until lots)
    }

    @Test fun windSteersRainCloudsAndTheArrow() {
        // From the west: blows east, rain leans right, clouds drift right, arrow points right.
        assertTrue(ScenePhysics.rainAngle(0.5, 20.0) > 0.3)
        assertTrue(ScenePhysics.rainAngle(0.5, -20.0) < -0.3)
        assertEquals(0.0, ScenePhysics.rainAngle(0.5, 0.0), 1e-9)
        assertTrue(ScenePhysics.cloudSpeed(20.0) > ScenePhysics.cloudSpeed(2.0))
        assertTrue(ScenePhysics.cloudSpeed(-20.0) < 0)
        val c = SceneModel.at(data(0, 0, 0.0, windFrom = 270.0, wind = 10.0), now, live = true)
        assertEquals(90.0, c.windTowardDeg, 1e-9)
        val (x, y) = ScenePhysics.arrow(c.windTowardDeg)
        assertEquals(1.0, x, 1e-9); assertEquals(0.0, y, 1e-9)
        // From the north-east: blows south-west, so the arrow points down and left.
        val (x2, y2) = ScenePhysics.arrow(SceneModel.at(data(0, 0, 0.0, windFrom = 45.0), now, true).windTowardDeg)
        assertTrue(x2 < 0 && y2 > 0)
    }

    @Test fun scrubbingPlaysTheForecast() {
        val d = data(0, 5, 0.0)
        val live = SceneModel.at(d, now, live = true)
        assertTrue(live.live)
        assertEquals(11.0, live.tempC, 1e-9)
        val h0 = now / hour * hour
        // Three hours on, it's raining in the forecast: the scene follows.
        val t = h0 + 3 * hour
        val wet = SceneModel.at(d, t, live = false)
        assertFalse(wet.live)
        assertEquals(63, wet.code)
        assertTrue(wet.rain > 0.4)
        assertEquals(11.5, wet.tempC, 1e-9)
        assertEquals(80, wet.precipProb)
        // Between hours it's in between.
        assertEquals(11.75, SceneModel.at(d, t + hour / 2, live = false).tempC, 1e-9)
        assertEquals(WeatherText.f(11.5) to 80, DayBar.readout(d, t))
        // Night: the sun is down, stars come out.
        val night = SceneModel.at(d, h0 + 12 * hour, live = false)
        assertTrue(night.sunAltDeg < -12 && night.darkness == 1.0)
        assertNotEquals(live.sunAltDeg, night.sunAltDeg)
    }

    @Test fun dayBar() {
        val start = DayBar.start(now)
        assertEquals(now / hour * hour - 2 * hour, start)
        assertEquals(start + 24 * hour, DayBar.end(now))
        val fNow = DayBar.fractionOf(now, now)
        assertTrue(abs(fNow - (2 + 5 / 60.0) / 24) < 1e-9)
        assertEquals(now.toDouble(), DayBar.timeAt(fNow, now).toDouble(), 1.0)
        val hours = DayBar.hours(data(0, 5, 0.0), now)
        assertEquals(25, hours.size)
        val (hi, lo) = DayBar.highLow(hours)!!
        assertTrue(hi.tempC > lo.tempC)
        assertTrue(hours.filter { DayBar.wet(it) }.size == 3)
    }

    @Test fun sunInTheRightPlace() {
        // Tacoma, June solstice noon (local solar noon ~13:17 PDT = 20:17 UTC): about 66° up, due south.
        val noon = java.time.LocalDateTime.of(2026, 6, 21, 20, 17).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        val (alt, az) = Sun.position(noon, 47.25, -122.44)
        assertEquals(66.2, alt * 180 / Math.PI, 1.0)
        assertEquals(0.0, az * 180 / Math.PI, 3.0)
        assertTrue(Sun.altitudeDeg(noon + 12 * hour, 47.25, -122.44) < -15)
    }
}
