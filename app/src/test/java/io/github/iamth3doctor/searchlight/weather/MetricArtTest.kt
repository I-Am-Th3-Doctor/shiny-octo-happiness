package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricArtTest {

    private val hour = 3_600_000L
    private val off = -8 * 3600
    // 2026-11-18 12:05 PST
    private val now = 1_795_550_400_000L + 5 * 60_000L
    private val dayStart = Moon.dayStart(now, off)

    /** Tomorrow: dry until 3 PM, rain 3 PM to 6 PM, dry after; pressure falling through the day. */
    private fun data(): WeatherData {
        val hours = (0 until 48).map { i ->
            val t = dayStart + i * hour
            val tomorrowHour = i - 24
            val wet = tomorrowHour in 15..17
            HourPoint(t, 5.0 + (i % 24) * 0.4, if (wet) 63 else 2, (i % 24) in 8..16,
                cloudPct = if (wet) 100 else 30, precipMm = if (wet) 2.5 else 0.0, precipProb = if (wet) 80 else 10,
                windMph = 5.0, windFromDeg = 340.0, pressureHpa = 1020.0 - i * 0.5)
        }
        val days = (0 until 7).map { i -> DayPoint(dayStart + i * 24 * hour, 9.0, 3.0, if (i == 1) 63 else 2, if (i == 1) 80 else 10,
            dayStart + i * 24 * hour + 7 * hour, dayStart + i * 24 * hour + 16 * hour + 30 * 60_000L) }
        val detail = WeatherDetail(off, 6.0, 81, 4.0, 1010.0, 24140.0, 4.0, 340.0, 1.0, hours, days, 61)
        return WeatherData("Tacoma", 47.25, -122.44, 7.0, 2, true, 9.0, 3.0, now, detail)
    }

    @Test fun theTimeLapsePlaysTheWholeDayInAboutNineSeconds() {
        assertTrue(DayLapse.PLAY_MS in 8_000L..10_000L)
        val tomorrow = dayStart + 24 * hour
        assertEquals(tomorrow, DayLapse.time(tomorrow, 0.0))
        assertTrue(DayLapse.time(tomorrow, 1.0) in tomorrow + 23 * hour until tomorrow + 24 * hour)
        assertEquals(tomorrow + 12 * hour, DayLapse.time(tomorrow, 0.5) + 30_000L) // half way: about noon
        assertEquals(0.0, DayLapse.progress(0), 1e-9)
        assertEquals(0.5, DayLapse.progress(DayLapse.PLAY_MS / 2), 1e-9)
        assertEquals("then it rests at the end", 1.0, DayLapse.progress(DayLapse.PLAY_MS * 3), 1e-9)
    }

    @Test fun rainArrivesAtItsHourAndStopsAtItsHour() {
        val d = data()
        val tomorrow = dayStart + 24 * hour
        assertEquals(listOf(tomorrow + 15 * hour to tomorrow + 18 * hour), DayLapse.wetWindows(d, tomorrow))
        assertEquals(emptyList<Pair<Long, Long>>(), DayLapse.wetWindows(d, dayStart))
        // Played through: dry at 2:59 PM, raining from 3 PM, dry again at 6 PM.
        assertEquals(0.0, SceneModel.at(d, tomorrow + 15 * hour - 60_000L, live = false).rain, 0.0)
        assertTrue(SceneModel.at(d, tomorrow + 15 * hour, live = false).rain > 0.4)
        assertTrue(SceneModel.at(d, tomorrow + 17 * hour + 59 * 60_000L, live = false).rain > 0.4)
        assertEquals(0.0, SceneModel.at(d, tomorrow + 18 * hour, live = false).rain, 0.0)
        // The readout rides along hour by hour.
        val r = DayLapse.readout(d, tomorrow + 15 * hour + 20 * 60_000L)
        assertEquals(tomorrow + 15 * hour, r.hourMs)
        assertEquals(80, r.precipProb)
        assertEquals(63, r.code)
        assertEquals(5.0 + 15 * 0.4, r.tempC, 1e-9)
        val dry = DayLapse.readout(d, tomorrow + 14 * hour + 59 * 60_000L)
        assertEquals(10, dry.precipProb)
    }

    @Test fun theSunRisesArcsAndSetsAndTheMoonHasTheNight() {
        val d = data()
        val tomorrow = dayStart + 24 * hour
        val dawn = SceneModel.at(d, tomorrow + 3 * hour, live = false)
        val noon = SceneModel.at(d, tomorrow + 12 * hour, live = false)
        val dusk = SceneModel.at(d, tomorrow + 21 * hour, live = false)
        assertTrue(dawn.sunAltDeg < 0 && dusk.sunAltDeg < 0)
        assertTrue(noon.sunAltDeg > 10)
        assertTrue("east in the morning, west after", SceneModel.at(d, tomorrow + 9 * hour, live = false).sunAz < 0 &&
            SceneModel.at(d, tomorrow + 15 * hour, live = false).sunAz > 0)
        // Days past the hourly forecast still play, from the day's own forecast.
        val later = dayStart + 5 * 24 * hour
        assertTrue(DayLapse.readout(d, later + 13 * hour).let { it.hourMs == later + 13 * hour && it.code == 2 })
        // A wet day past the hourly forecast: wet all day, as the scene plays it.
        val wetDay = d.copy(detail = d.detail!!.copy(daily = d.detail!!.daily.mapIndexed { i, x -> if (i == 5) x.copy(code = 95, precipMm = 12.0) else x }))
        assertEquals(listOf(later to later + 24 * hour), DayLapse.wetWindows(wetDay, later))
        assertEquals(emptyList<Pair<Long, Long>>(), DayLapse.wetWindows(d, later))
    }

    @Test fun sunArcAndDaylight() {
        val rise = dayStart + 7 * hour
        val set = dayStart + 16 * hour + 30 * 60_000L
        assertEquals(0.0, MetricArt.sunFraction(rise, set, rise), 1e-9)
        assertEquals(1.0, MetricArt.sunFraction(rise, set, set), 1e-9)
        assertEquals(0.5, MetricArt.sunFraction(rise, set, (rise + set) / 2), 1e-9)
        assertTrue(MetricArt.sunFraction(rise, set, dayStart + 2 * hour) < 0)
        assertEquals("9h 30m", MetricArt.duration(set - rise))
        assertEquals("0h 05m", MetricArt.duration(5 * 60_000L))
    }

    @Test fun theMoonsDay() {
        val m = MetricArt.moonDay(dayStart, 47.25, -122.44)
        assertEquals(24 * 60 / MetricArt.STEP_MIN, m.steps)
        // Up whenever the moon's altitude says so; visible only when it's also dark.
        for (k in 0 until m.steps) {
            val t = dayStart + k * MetricArt.STEP_MIN * 60_000L
            assertEquals(Moon.altitude(t, 47.25, -122.44) > 0, m.up[k])
            assertEquals(m.up[k] && m.dark[k], m.visible[k])
        }
        assertTrue("noon in Tacoma isn't dark", !m.dark[12 * 60 / MetricArt.STEP_MIN])
        assertTrue("midnight is", m.dark[0])
        assertEquals(Moon.age(now) < 0.5, MetricArt.waxing(now))
    }

    @Test fun humidityVisibilityPressure() {
        assertEquals("air is close to saturation", MetricArt.humidityNote(5.0, 4.0))
        assertEquals("dry air", MetricArt.humidityNote(30.0, 0.0))
        assertEquals(1.0, MetricArt.visibilityFraction(24140.0), 1e-9) // 15 mi: past the far mountains
        assertEquals(0.5, MetricArt.visibilityFraction(5 * 1609.344), 1e-9)
        assertEquals(1.0, MetricArt.clarity(0.0, 15.0), 1e-9)
        assertTrue(MetricArt.clarity(1.0, 15.0) > MetricArt.clarity(10.0, 15.0))
        assertEquals("beyond what you can see: gone", 0.0, MetricArt.clarity(6.0, 3.0), 1e-9)
        // 29.83 inHg sits a little past half way on a 28.6–31.0 gauge.
        val hpa = 29.83 / 0.0295299830714
        assertEquals((29.83 - 28.6) / 2.4, MetricArt.pressureFraction(hpa), 1e-6)
        assertEquals(0.0, MetricArt.pressureFraction(900.0), 1e-9)
        val d = data()
        assertEquals("falling half a hPa an hour", MetricArt.Trend.FALLING, MetricArt.pressureTrend(d.detail!!.hourly, now))
        val rising = d.detail!!.hourly.map { it.copy(pressureHpa = 1000.0 + (it.time - dayStart) / hour * 0.5) }
        assertEquals(MetricArt.Trend.RISING, MetricArt.pressureTrend(rising, now))
        val steady = d.detail!!.hourly.map { it.copy(pressureHpa = 1013.0) }
        assertEquals(MetricArt.Trend.STEADY, MetricArt.pressureTrend(steady, now))
        assertNull("no hourly pressure: no trend", MetricArt.pressureTrend(d.detail!!.hourly.map { it.copy(pressureHpa = null) }, now))
        assertTrue(MetricArt.Trend.RISING.note.contains("improving"))
    }

    @Test fun uvAirAndWind() {
        assertTrue(MetricArt.uvIntensity(1.0) < 0.2)
        assertEquals(1.0, MetricArt.uvIntensity(12.0), 1e-9)
        assertEquals("no protection needed", MetricArt.uvAdvice(1.0))
        // Six equal bands: 61 is a little into the second (Moderate).
        assertEquals((1 + 11.0 / 50) / 6, MetricArt.aqiFraction(61), 1e-9)
        assertEquals(0.0, MetricArt.aqiFraction(0), 1e-9)
        assertEquals(1.0, MetricArt.aqiFraction(600), 1e-9)
        assertTrue(MetricArt.aqiParticles(150) > MetricArt.aqiParticles(20))
        assertEquals("gentle drift", MetricArt.windWord(4.0))
        assertEquals("calm", MetricArt.windWord(0.3))
        assertTrue(MetricArt.streamSpeedDp(25.0) > MetricArt.streamSpeedDp(4.0))
        assertFalse(MetricArt.windWord(30.0) == MetricArt.windWord(4.0))
    }

    @Test fun hourlyPressureRoundTrips() {
        val d = data().detail!!
        val back = WeatherDetail.fromJson(d.toJson())
        assertEquals(d.hourly.map { it.pressureHpa }, back.hourly.map { it.pressureHpa })
    }
}
