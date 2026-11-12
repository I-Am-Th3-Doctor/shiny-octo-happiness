package io.github.iamth3doctor.searchlight.calendar

import io.github.iamth3doctor.searchlight.calendar.NextUp.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextUpTest {

    private val now = 1_800_000_000_000L
    private fun min(n: Long) = n * 60_000L
    private fun ev(id: Long, title: String, startIn: Long, length: Long = 30, location: String = "", description: String = "", allDay: Boolean = false) =
        DayEvent(id, title, now + min(startIn), now + min(startIn + length), allDay, "Work", 0, location = location, description = description)

    @Test fun startingWithinTheHour() {
        val s = NextUp.pick(listOf(ev(1, "Team Sync", 14)), now)!!
        assertEquals(State.STARTING, s.state)
        assertEquals(14, s.minutes)
        assertEquals("Team Sync starts in 14 min", NextUp.text(s, "(No title)"))
        // Rounded up: 30 s away is "1 min", never "0 min".
        assertEquals(1, NextUp.pick(listOf(DayEvent(2, "Soon", now + 30_000, now + min(30), false, "", 0)), now)!!.minutes)
        // Exactly 60 min away counts; 61 doesn't.
        assertEquals("Hour starts in 60 min", NextUp.text(NextUp.pick(listOf(ev(3, "Hour", 60)), now)!!, ""))
        assertNull(NextUp.pick(listOf(ev(4, "Later", 61)), now))
    }

    @Test fun inProgressWinsAndCountsDownToItsEnd() {
        val events = listOf(ev(1, "Design Review", -8, 30), ev(2, "Team Sync", 10))
        val s = NextUp.pick(events, now)!!
        assertEquals(State.IN_PROGRESS, s.state)
        assertEquals("In progress: Design Review (Ends in 22 min)", NextUp.text(s, ""))
        // Two at once: the one ending first.
        assertEquals("Short", NextUp.pick(listOf(ev(1, "Long", -10, 120), ev(2, "Short", -5, 20)), now)!!.event.title)
        // Longer ones read in hours.
        assertEquals("In progress: Offsite (Ends in 1 hr 10 min)", NextUp.text(NextUp.pick(listOf(ev(3, "Offsite", -20, 90)), now)!!, ""))
        assertEquals("2 hr", NextUp.duration(120))
    }

    @Test fun nothingSoonOrEndedOrAllDayIsWeather() {
        assertNull(NextUp.pick(emptyList(), now))
        assertNull(NextUp.pick(listOf(ev(1, "Done", -60, 30)), now))
        // It ended exactly now: over.
        assertNull(NextUp.pick(listOf(ev(2, "Just ended", -30, 30)), now))
        assertNull(NextUp.pick(listOf(ev(3, "Holiday", -60, 24 * 60, allDay = true)), now))
        // The soonest of several upcoming.
        assertEquals("B", NextUp.pick(listOf(ev(4, "A", 40), ev(5, "B", 5)), now)!!.event.title)
        assertEquals("(No title) starts in 5 min", NextUp.text(NextUp.pick(listOf(ev(6, "", 5)), now)!!, "(No title)"))
    }

    @Test fun videoCallPlatforms() {
        fun line(location: String = "", description: String = "") = NextUp.text(NextUp.pick(listOf(ev(1, "Call", 14, location = location, description = description)), now)!!, "")
        assertEquals("Call starts in 14 min (Zoom)", line(location = "https://us02web.zoom.us/j/123456?pwd=abc"))
        assertEquals("Call starts in 14 min (Meet)", line(description = "Join: https://meet.google.com/abc-defg-hij\nOr dial in"))
        assertEquals("Call starts in 14 min (Teams)", line(description = "<https://teams.microsoft.com/l/meetup-join/19%3ameeting>"))
        assertEquals("Call starts in 14 min (video link)", line(location = "https://acme.webex.com/meet/jane"))
        // A link that isn't a call (a map, a doc): no suffix.
        assertEquals("Call starts in 14 min", line(location = "https://maps.google.com/?q=Cafe", description = "Agenda: https://docs.google.com/d/1"))
        // Named platforms win over another call link; the location is looked at first.
        assertEquals("Zoom", NextUp.callIn("https://meet.jit.si/x then zoom.us/j/9")!!.platform)
        assertEquals("https://zoom.us/j/9", NextUp.callIn("dial zoom.us/j/9.")!!.url)
        assertEquals("https://meet.google.com/abc", NextUp.callOf(ev(2, "x", 1, location = "https://meet.google.com/abc", description = "https://zoom.us/j/1"))!!.url)
        assertNull(NextUp.callIn("Room 4, Building B"))
    }

    @Test fun dismissingOneOccurrence() {
        val sync = ev(1, "Team Sync", -5, 30)
        val next = ev(2, "Design Review", 40)
        val dismissed = NextUp.keys(setOf(NextUp.entry(sync)))
        // Dismissed: the in-progress one is skipped; the next one in its window still takes over.
        assertEquals("Design Review", NextUp.pick(listOf(sync, next), now, dismissed = dismissed)!!.event.title)
        assertNull(NextUp.pick(listOf(sync), now, dismissed = dismissed))
        // Another occurrence of the same event (same id, another start) isn't dismissed.
        val nextWeek = DayEvent(1, "Team Sync", now + min(5), now + min(35), false, "Work", 0)
        assertEquals(State.STARTING, NextUp.pick(listOf(nextWeek), now, dismissed = dismissed)!!.state)
        // Once it's ended the stored dismissal goes; until then it stays.
        val stored = setOf(NextUp.entry(sync))
        assertEquals(stored, NextUp.live(stored, now))
        assertEquals(emptySet<String>(), NextUp.live(stored, sync.end))
        assertEquals(emptySet<String>(), NextUp.live(setOf("junk"), now))
    }
}
