package io.github.iamth3doctor.searchlight.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoFavoritesTest {

    private val apps = mapOf("mail" to "0/mail/.Main", "maps" to "0/maps/.Main", "chat" to "0/chat/.Main", "music" to "0/music/.Main")

    @Test fun ranksByLaunchesNotScreens() {
        // Three screens inside maps in a row are one launch; mail is launched twice.
        val events = listOf("maps" to 1L, "maps" to 2L, "maps" to 3L, "home" to 4L, "mail" to 5L, "home" to 6L, "mail" to 7L)
        assertEquals(listOf("0/mail/.Main", "0/maps/.Main"), AutoFavorites.rank(events, apps))
    }

    @Test fun tiesGoToTheMostRecent() {
        val events = listOf("chat" to 1L, "home" to 2L, "music" to 3L)
        assertEquals(listOf("0/music/.Main", "0/chat/.Main"), AutoFavorites.rank(events, apps))
    }

    @Test fun uninstalledAndExcludedAppsNeverRank() {
        // "gone" was uninstalled and "home" is Searchlight: neither is a candidate.
        val events = listOf("gone" to 1L, "home" to 2L, "gone" to 3L, "home" to 4L, "chat" to 5L)
        assertEquals(listOf("0/chat/.Main"), AutoFavorites.rank(events, apps))
    }

    @Test fun keepsAFewSpares() {
        val many = (1..20).associate { "p$it" to "k$it" }
        val events = (1..20).flatMap { i -> List(i) { listOf("p$i" to i.toLong(), "home" to 0L) }.flatten() }
        assertEquals((20 downTo 9).map { "k$it" }, AutoFavorites.rank(events, many))
    }

    @Test fun predictionFavoursAppsUsedAtThisTimeOfDay() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val h = 60L * 60 * 1000
        val day = 24 * h
        // Maps: three launches a day at 8:00. Music: five launches, all at 20:00.
        val events = (0 until 3).flatMap { d -> listOf("maps" to d * day + 8 * h, "home" to d * day + 8 * h + 1) } +
            (0 until 5).flatMap { d -> listOf("music" to d * day + 20 * h, "home" to d * day + 20 * h + 1) }
        val sorted = events.sortedBy { it.second }
        val morning = AutoFavorites.timeOfDayRanking(sorted, apps, now = 10 * day + 8 * h, zone = utc)
        val evening = AutoFavorites.timeOfDayRanking(sorted, apps, now = 10 * day + 20 * h, zone = utc)
        assertEquals("0/maps/.Main", morning.first())
        assertEquals("0/music/.Main", evening.first())
    }

    @Test fun predictionCountsLaunchesNotScreensAndSkipsNonCandidates() {
        val utc = java.util.TimeZone.getTimeZone("UTC")
        val events = listOf("chat" to 1L, "chat" to 2L, "chat" to 3L, "home" to 4L, "gone" to 5L, "home" to 6L, "gone" to 7L)
        assertEquals(listOf("0/chat/.Main"), AutoFavorites.predict(events, apps, now = 10L, zone = utc))
    }

    // ---- The predicted 6th favorite: recency first, then today, then the time-of-day habit ----

    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private val min = 60L * 1000
    private val hr = 60 * min
    private val day = 24 * hr
    private val now = 10 * day + 15 * hr // day 10, 15:00

    /** [pkg] launched at each of [times] (going home in between, so each is its own launch). */
    private fun launches(pkg: String, vararg times: Long) = times.flatMap { listOf(pkg to it, "home" to it + 1) }

    private fun events(vararg groups: List<Pair<String, Long>>) = groups.toList().flatten().sortedBy { it.second }

    @Test fun recencyDecay() {
        assertEquals(1.0, AutoFavorites.recencyWeight(0), 0.0)
        assertEquals(1.0, AutoFavorites.recencyWeight(30 * min), 0.0)
        assertEquals(0.5, AutoFavorites.recencyWeight(30 * min + 1), 0.0)
        assertEquals(0.5, AutoFavorites.recencyWeight(60 * min), 0.0)
        assertEquals(0.25, AutoFavorites.recencyWeight(61 * min), 0.0)
        assertEquals(0.25, AutoFavorites.recencyWeight(120 * min), 0.0)
        assertEquals(0.0, AutoFavorites.recencyWeight(120 * min + 1), 0.0)
        assertEquals(1.0, AutoFavorites.recencyWeight(-5 * min), 0.0) // stamped a moment ahead: now
    }

    @Test fun threeLaunchesThisHalfHourBeatWeeksOfHabitAtThisHour() {
        // Maps: every afternoon at 15:00 for the past nine days, not today. Chat: three times in the last 30 minutes.
        val habit = launches("maps", *(1L..9L).map { (10 - it) * day + 15 * hr }.toLongArray())
        val recent = launches("chat", now - 25 * min, now - 15 * min, now - 5 * min)
        assertEquals("0/chat/.Main", AutoFavorites.predict(events(habit, recent), apps, now, utc).first())
    }

    @Test fun decayWeighsHowRecentNotJustHowMany() {
        // Chat once 10 minutes ago (1.0) beats Mail three times about 90 minutes ago (0.75)...
        val chat = launches("chat", now - 10 * min)
        val mail = launches("mail", now - 95 * min, now - 90 * min, now - 85 * min)
        assertEquals(listOf("0/chat/.Main", "0/mail/.Main"), AutoFavorites.recentRanking(events(chat, mail), apps, now))
        // ...but three times 45 minutes ago (1.5) beats it.
        val mailSooner = launches("mail", now - 50 * min, now - 45 * min, now - 40 * min)
        assertEquals("0/mail/.Main", AutoFavorites.recentRanking(events(chat, mailSooner), apps, now).first())
        // Older than two hours: nothing.
        assertEquals(emptyList<String>(), AutoFavorites.recentRanking(launches("chat", now - 3 * hr), apps, now))
    }

    @Test fun noLaunchesInTwoHoursFallsBackToToday() {
        // Nothing since 12:00. Music this morning; Maps the long-run habit at this hour.
        val habit = launches("maps", *(1L..9L).map { (10 - it) * day + 15 * hr }.toLongArray())
        val today = launches("music", now - 7 * hr)
        val p = AutoFavorites.predict(events(habit, today), apps, now, utc)
        assertEquals(listOf("0/music/.Main", "0/maps/.Main"), p.take(2))
    }

    @Test fun nothingTodayFallsBackToTheTimeOfDayHabit() {
        val habit = launches("maps", *(1L..9L).map { (10 - it) * day + 15 * hr }.toLongArray())
        val evening = launches("music", *(1L..9L).map { (10 - it) * day + 21 * hr }.toLongArray())
        assertEquals("0/maps/.Main", AutoFavorites.predict(events(habit, evening), apps, now, utc).first())
    }

    @Test fun nothingAtAllPredictsNothing() {
        assertEquals(emptyList<String>(), AutoFavorites.predict(emptyList(), apps, now, utc))
    }

    @Test fun recentAppsAlreadyInSlotsOneToFiveFallThroughToToday() {
        // The only recent launch is Chat, already favorite #1: slot 6 shows today's Music instead.
        val recent = launches("chat", now - 5 * min)
        val today = launches("music", now - 6 * hr)
        val predictions = AutoFavorites.predict(events(recent, today), apps, now, utc)
        val slots = FavoriteSlots.compose(
            favorites = listOf("0/chat/.Main", "a", "b", "c", "d", "manual6"),
            predictedSlot = true, pinned = false, predictions = predictions, installed = { true },
        )
        assertEquals("0/music/.Main", slots[5]!!.key)
        assertEquals(FavoriteSlots.Kind.PREDICTED, slots[5]!!.kind)
    }

    @Test fun noPredictionAtAllLeavesTheManualSixth() {
        val slots = FavoriteSlots.compose(
            favorites = listOf("a", "b", "c", "d", "e", "manual6"),
            predictedSlot = true, pinned = false, predictions = AutoFavorites.predict(emptyList(), apps, now, utc), installed = { true },
        )
        assertEquals("manual6", slots[5]!!.key)
        assertEquals(FavoriteSlots.Kind.SLOT6_FIXED, slots[5]!!.kind)
    }
}
