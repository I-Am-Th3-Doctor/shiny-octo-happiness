package io.github.iamth3doctor.searchlight.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsPrecipTest {

    private val hour = 3_600_000L
    private val now = 1_795_550_400_000L // 2026-11-18 20:00 UTC (12:00 PST)

    private fun iso(ms: Long) = java.time.Instant.ofEpochMilli(ms).atOffset(java.time.ZoneOffset.ofHours(-8)).toString()

    private fun feature(id: String, event: String, severity: String, eff: Long, exp: Long, status: String = "Actual", type: String = "Alert") = """
        {"id":"$id","type":"Feature","properties":{"id":"$id","event":"$event","headline":"$event issued by NWS Seattle WA",
         "severity":"$severity","status":"$status","messageType":"$type","effective":"${iso(eff)}","onset":"${iso(eff)}",
         "expires":"${iso(exp)}","ends":null,"description":"* WHAT...Strong storms.\n* WHERE...Pierce County.","instruction":"Move indoors.",
         "senderName":"NWS Seattle WA"}}
    """.trimIndent()

    private fun response(vararg f: String) = """{"type":"FeatureCollection","features":[${f.joinToString(",")}]}"""

    @Test fun parsesActiveAlertsMostSevereFirst() {
        val json = response(
            feature("a1", "Wind Advisory", "Moderate", now - hour, now + 6 * hour),
            feature("a2", "Severe Thunderstorm Warning", "Severe", now - hour, now + 2 * hour),
            feature("a3", "Flood Watch", "Moderate", now - 10 * hour, now - hour), // expired
            feature("a4", "Test Message", "Minor", now - hour, now + hour, status = "Test"),
            feature("a5", "Winter Storm Warning", "Severe", now - hour, now + hour, type = "Cancel"),
        )
        val alerts = NwsAlerts.parse(json, now)
        assertEquals(listOf("a2", "a1"), alerts.map { it.id })
        val a = alerts.first()
        assertEquals("Severe Thunderstorm Warning", a.event)
        assertEquals(now - hour, a.effective)
        assertEquals(now + 2 * hour, a.expires)
        assertEquals("Move indoors.", a.instruction)
        assertTrue(a.description.contains("Pierce County"))
        assertEquals(emptyList<WeatherAlert>(), NwsAlerts.parse(response(), now))
        assertEquals(emptyList<WeatherAlert>(), NwsAlerts.parse("""{"features":[]}""", now))
    }

    @Test fun pillsAndKinds() {
        fun alert(event: String) = WeatherAlert("x", event, "", "Severe", null, null, "", "", "")
        assertEquals("! THUNDERSTORM !", NwsAlerts.pill(alert("Severe Thunderstorm Warning")))
        assertEquals("! FLOOD WATCH !", NwsAlerts.pill(alert("Flood Watch")))
        assertEquals("! WIND ADVISORY !", NwsAlerts.pill(alert("Wind Advisory")))
        assertEquals(AlertKind.THUNDER, NwsAlerts.kind("Tornado Warning"))
        assertEquals(AlertKind.SNOW, NwsAlerts.kind("Winter Storm Warning"))
        assertEquals(AlertKind.RAIN, NwsAlerts.kind("Flash Flood Warning"))
        assertEquals(AlertKind.WIND, NwsAlerts.kind("High Wind Warning"))
        assertEquals(AlertKind.FOG, NwsAlerts.kind("Dense Fog Advisory"))
        assertEquals(AlertKind.HEAT, NwsAlerts.kind("Excessive Heat Warning"))
        assertTrue(NwsAlerts.url(47.2529, -122.4443).endsWith("/alerts/active?point=47.2529,-122.4443"))
    }

    @Test fun alertsSurviveTheCache() {
        val a = WeatherAlert("id1", "Flood Watch", "Flood Watch issued", "Moderate", now, now + hour, "desc", "inst", "NWS")
        val w = WeatherData("Tacoma", 47.25, -122.44, 10.0, 61, true, 12.0, 6.0, now, null, listOf(a))
        assertEquals(w, WeatherData.fromJson(w.toJson()))
        val none = w.copy(alerts = emptyList())
        assertEquals(none, WeatherData.fromJson(none.toJson()))
        assertNull(WeatherData.fromJson(w.copy(alerts = null).toJson())!!.alerts)
    }

    private fun data(alerts: List<WeatherAlert>? = null): WeatherData {
        val h0 = now / hour * hour
        val hours = (-2..30).map { i ->
            val wet = i in 3..4
            HourPoint(h0 + i * hour, 10.0, if (wet) 63 else 1, true, cloudPct = if (wet) 100 else 20,
                precipMm = if (wet) 2.286 else 0.0, precipProb = if (wet) 78 else 5, windMph = 5.0, windFromDeg = 180.0)
        }
        val days = (0 until 7).map { i -> DayPoint(h0 - 12 * hour + i * 24 * hour, 12.0, 5.0, if (i == 2) 73 else 61, 40 + i, null, null, 1.0 + i) }
        val d = WeatherDetail(-28800, 9.0, 80, 5.0, 1015.0, 20000.0, 5.0, 180.0, 1.0, hours, days, 20, cloudPct = 20, precipMm = 0.0)
        return WeatherData("Tacoma", 47.25, -122.44, 10.0, 1, true, 12.0, 5.0, now, d, alerts)
    }

    @Test fun precipRowsFollowTheHour() {
        val d = data()
        val dry = Precip.at(d, now)
        assertEquals(0.0, dry.rateIn, 1e-9)
        assertEquals(5, dry.chance)
        assertEquals(Precip.Type.NONE, dry.type)
        val wet = Precip.at(d, now / hour * hour + 3 * hour + 10 * 60_000L)
        assertEquals("0.09 IN", Precip.format(wet.rateIn))
        assertEquals(78, wet.chance)
        assertEquals(Precip.Type.RAIN, wet.type)
        // Rain expected around the hour it starts, from the hourly forecast.
        assertEquals(now / hour * hour + 3 * hour, Precip.nextWet(d, now)!!.time)
        assertNull(Precip.nextWet(d, now, withinMs = 2 * hour))
        // The week: a day's total and chance; snow on a snowy day.
        val day3 = Precip.day(d, now + 2 * 24 * hour)
        assertEquals(Precip.Type.SNOW, day3.type)
        assertEquals(42, day3.chance)
        assertEquals(3.0 / 25.4, day3.rateIn, 1e-9)
    }

    @Test fun anAlertDarkensTheSceneButNeverMakesRainTheConditionHasnt() {
        val calm = SceneModel.at(data(), now, live = true)
        assertNull(calm.severe)
        assertTrue(!calm.thunder && calm.rain == 0.0)
        val storm = WeatherAlert("s", "Severe Thunderstorm Warning", "", "Severe", now - hour, now + 5 * hour, "", "", "")
        // Dry now: a dark, stormy sky, but no rain, no lightning, no sun.
        val dry = SceneModel.at(data(listOf(storm)), now, live = true)
        assertEquals(AlertKind.THUNDER, dry.severe)
        assertTrue(dry.cloudCover == 1.0 && dry.rain == 0.0 && !dry.thunder)
        // Raining (the forecast's 3 AM-ish hour): the storm it warns of, harder.
        val wet = SceneModel.at(data(listOf(storm)), now / hour * hour + 3 * hour, live = false)
        assertTrue(wet.thunder && wet.rain >= 0.85 && wet.cloudCover == 1.0)
        // After it expires, the forecast's own weather again.
        assertNull(SceneModel.at(data(listOf(storm)), now + 6 * hour, live = false).severe)
        val snow = WeatherAlert("w", "Winter Storm Warning", "", "Severe", null, null, "", "", "")
        val s = SceneModel.at(data(listOf(snow)), now, live = true)
        assertTrue(s.snow == 0.0 && s.rain == 0.0 && s.cloudCover == 1.0)
    }

    @Test fun theSkyRule() {
        assertEquals(SceneModel.Sky.SUN, SceneModel.sky(0, 0.0, 5))
        assertEquals(SceneModel.Sky.SUN, SceneModel.sky(1, null, null))
        // Partly cloudy, overcast, fog: cloud, never the sun.
        assertEquals(SceneModel.Sky.CLOUD, SceneModel.sky(2, 0.0, 10))
        assertEquals(SceneModel.Sky.CLOUD, SceneModel.sky(3, 0.0, 44))
        assertEquals(SceneModel.Sky.CLOUD, SceneModel.sky(45, 0.0, 0))
        // Rain: a rain or shower condition, any rain falling, or a 50% chance.
        assertEquals(SceneModel.Sky.RAIN, SceneModel.sky(61, 0.0, 10))
        assertEquals(SceneModel.Sky.RAIN, SceneModel.sky(80, 0.0, 10))
        assertEquals(SceneModel.Sky.RAIN, SceneModel.sky(3, 0.1, 10))
        assertEquals(SceneModel.Sky.RAIN, SceneModel.sky(3, 0.0, 50))
        assertEquals(SceneModel.Sky.RAIN, SceneModel.sky(0, 0.0, 60))
        assertEquals(SceneModel.Sky.SNOW, SceneModel.sky(73, 1.0, 90))
    }

    /**
     * The Renton afternoon in the report: Overcast, 54°F, cloud 100%, no rain falling, a 44% chance
     * this hour; light rain from 5 PM, a 60% chance at 6 AM; and an alert in effect.
     */
    private fun overcastBeforeRain(alerts: List<WeatherAlert>? = null): WeatherData {
        val h0 = now / hour * hour
        val hours = (-2..30).map { i ->
            val rain = i in 2..10
            HourPoint(h0 + i * hour, 12.0, if (rain) 61 else 3, true, cloudPct = 100,
                precipMm = if (rain) 0.3 else 0.0, precipProb = when { i == 15 -> 60; rain -> 45; else -> 44 },
                windMph = 8.0, windFromDeg = 180.0)
        }
        val days = (0 until 7).map { i -> DayPoint(h0 - 12 * hour + i * 24 * hour, 13.0, 8.0, 61, 60, null, null, 4.0) }
        val d = WeatherDetail(-28800, 11.0, 90, 10.0, 1010.0, 16000.0, 8.0, 180.0, 0.5, hours, days, 30, cloudPct = 100, precipMm = 0.0)
        return WeatherData("Renton", 47.48, -122.22, 12.2, 3, true, 13.0, 8.0, now, d, alerts)
    }

    @Test fun overcastShowsCloudNotSunAndTheCardAgreesWithTheStrip() {
        val flood = WeatherAlert("f", "Flood Watch", "", "Moderate", now - hour, now + 24 * hour, "", "", "")
        for (alerts in listOf(null, listOf(flood))) {
            val d = overcastBeforeRain(alerts)
            // The scene: gray cloud, no sun, no rain streaks while the page says Overcast and no rain.
            val c = SceneModel.at(d, now, live = true)
            assertEquals(SceneModel.Sky.CLOUD, c.sky)
            assertEquals("rain streaks under $alerts", 0.0, c.rain, 0.0)
            assertTrue(c.cloudCover >= 0.9)
            // The hero's callout says what the scene shows.
            assertEquals("Rain None", HeroReadings.precipLine(c))
            // The precip card's island: the same sky (no sun); TYPE None now.
            val r = Precip.now(d, now)
            assertEquals(SceneModel.Sky.CLOUD, r.sky)
            assertEquals(Precip.Type.NONE, r.type)
            assertEquals(44, r.chance)
        }
        val d = overcastBeforeRain()
        // Rain expected at the first hour the strip shows rain (5 PM here), not the 60% hour later on.
        assertEquals(now / hour * hour + 2 * hour, Precip.nextWet(d, now)!!.time)
        // Once it rains: rain on the island, in the scene, and in the words.
        val t = now / hour * hour + 2 * hour
        val wet = SceneModel.at(d, t, live = false)
        assertEquals(SceneModel.Sky.RAIN, wet.sky)
        assertTrue(wet.rain > 0)
        assertEquals(Precip.Type.RAIN, Precip.at(d, t).type)
        assertEquals("Rain ${HeroReadings.rainRate(0.3)}", HeroReadings.precipLine(wet))
        // A 60% chance with nothing measured yet: rain, and the words say so.
        val likely = SceneModel.at(d, now / hour * hour + 15 * hour, live = false)
        assertEquals(SceneModel.Sky.RAIN, likely.sky)
        assertEquals("Rain likely · 60%", HeroReadings.precipLine(likely))
    }
}
