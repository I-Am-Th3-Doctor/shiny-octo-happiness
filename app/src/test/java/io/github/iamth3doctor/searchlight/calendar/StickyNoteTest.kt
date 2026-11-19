package io.github.iamth3doctor.searchlight.calendar

import io.github.iamth3doctor.searchlight.calendar.StickyNote.Ink
import io.github.iamth3doctor.searchlight.weather.WeatherSkin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class StickyNoteTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 11, 18) // a Wednesday
    private fun at(h: Int, m: Int = 0, d: LocalDate = day) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun utc(d: LocalDate) = d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun ev(id: Long, title: String, from: Long, to: Long, allDay: Boolean = false) =
        DayEvent(id, title, from, to, allDay, "Work", 0)
    private val time: (Long) -> String = { ms ->
        val t = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        "%d:%02d %s".format((t.hour + 11) % 12 + 1, t.minute, if (t.hour < 12) "AM" else "PM")
    }

    @Test fun theDateInMarker() {
        assertEquals("WED NOV 18", StickyNote.dateLabel(day, Locale.US))
        assertEquals("SUN MAR 1", StickyNote.dateLabel(LocalDate.of(2026, 3, 1), Locale.US))
    }

    @Test fun todaysEventsInTimeOrderNextHighlightedPastCheckedOff() {
        val events = listOf(
            ev(3, "Lunch", at(12), at(13)),
            ev(1, "Team standup", at(9), at(9, 15)),
            ev(4, "Tomorrow's thing", at(9, d = day.plusDays(1)), at(10, d = day.plusDays(1))),
            ev(2, "Design review", at(10, 30), at(11, 30)),
            ev(5, "Yesterday's", at(9, d = day.minusDays(1)), at(10, d = day.minusDays(1))),
        )
        val note = StickyNote.note(events, at(10, 45), zone, Locale.US, time)
        assertEquals(day, note.day)
        assertEquals("WED NOV 18", note.dateLabel)
        assertEquals(listOf("Team standup", "Design review", "Lunch"), note.lines.map { it.event.title })
        // Standup is over; the review is on now (the next one); lunch is later.
        assertEquals(listOf(Ink.DONE, Ink.NEXT, Ink.PLAIN), note.lines.map { it.ink })
        assertEquals(0, note.more)
        assertEquals("9:00 AM — Team standup", StickyNote.lineText(note.lines[0], "All day", "(No title)"))
        // Once the review is over, lunch is next.
        val later = StickyNote.note(events, at(11, 30), zone, Locale.US, time)
        assertEquals(listOf(Ink.DONE, Ink.DONE, Ink.NEXT), later.lines.map { it.ink })
        // And after lunch, nothing is next: all three checked off.
        val evening = StickyNote.note(events, at(19), zone, Locale.US, time)
        assertEquals(listOf(Ink.DONE, Ink.DONE, Ink.DONE), evening.lines.map { it.ink })
    }

    @Test fun allDayFirstNeverCheckedOffNorNext() {
        val events = listOf(ev(2, "Dentist", at(15), at(16)), ev(1, "Holiday", utc(day), utc(day.plusDays(1)), allDay = true))
        val note = StickyNote.note(events, at(23, 30), zone, Locale.US, time)
        assertEquals(listOf("Holiday", "Dentist"), note.lines.map { it.event.title })
        assertEquals(listOf(Ink.PLAIN, Ink.DONE), note.lines.map { it.ink })
        assertNull(note.lines[0].time)
        assertEquals("All day — Holiday", StickyNote.lineText(note.lines[0], "All day", "(No title)"))
        // An untitled event still reads.
        assertEquals("3:00 PM — (No title)", StickyNote.lineText(StickyNote.Line(ev(9, " ", at(15), at(16)), "3:00 PM", Ink.PLAIN), "All day", "(No title)"))
    }

    @Test fun nothingOnToday() {
        val note = StickyNote.note(listOf(ev(1, "Tomorrow", at(9, d = day.plusDays(1)), at(10, d = day.plusDays(1)))), at(8), zone, Locale.US, time)
        assertTrue(note.empty)
        assertEquals(0, note.more)
    }

    @Test fun moreThanThreeShowsTheFirstThreeAndHowManyMore() {
        val events = (0 until 5).map { ev(it.toLong(), "Busy $it", at(9 + it), at(9 + it, 30)) }
        val note = StickyNote.note(events.shuffled(java.util.Random(4)), at(8), zone, Locale.US, time)
        assertEquals(listOf("Busy 0", "Busy 1", "Busy 2"), note.lines.map { it.event.title })
        assertEquals(2, note.more)
        assertEquals(Ink.NEXT, note.lines[0].ink)
        assertEquals(1, StickyNote.note(events.take(4), at(8), zone, Locale.US, time).more)
        assertEquals(0, StickyNote.note(events.take(3), at(8), zone, Locale.US, time).more)
    }

    @Test fun midnightRollsToTheNewDay() {
        val events = listOf(ev(1, "Late", at(22), at(23)), ev(2, "Early", at(7, d = day.plusDays(1)), at(8, d = day.plusDays(1))))
        val before = StickyNote.note(events, at(23, 59), zone, Locale.US, time)
        val after = StickyNote.note(events, at(0, 0, d = day.plusDays(1)), zone, Locale.US, time)
        assertEquals("WED NOV 18", before.dateLabel)
        assertEquals(listOf("Late"), before.lines.map { it.event.title })
        assertEquals("THU NOV 19", after.dateLabel)
        assertEquals(listOf("Early"), after.lines.map { it.event.title })
        assertEquals(Ink.NEXT, after.lines.single().ink)
    }

    @Test fun fourPapersYellowFirstAndEveryInkReads() {
        assertEquals(listOf("yellow", "pink", "blue", "green"), StickyNote.Paper.entries.map { it.key })
        assertEquals(StickyNote.Paper.YELLOW, StickyNote.Paper.of(null))
        assertEquals(StickyNote.Paper.YELLOW, StickyNote.Paper.of("purple"))
        for (p in StickyNote.Paper.entries) {
            assertEquals(p, StickyNote.Paper.of(p.key))
            for (paper in listOf(p.top, p.bottom)) {
                assertTrue("${p.key} ink", WeatherSkin.contrast(p.ink, paper) >= 7.0)
                assertTrue("${p.key} highlight", WeatherSkin.contrast(p.highlight, paper) >= 4.5)
                val faded = (p.ink and 0xFFFFFF) or ((StickyNote.DONE_ALPHA * 255).toInt() shl 24)
                assertTrue("${p.key} checked off", WeatherSkin.contrast(WeatherSkin.over(faded, paper), paper) >= 4.5)
            }
        }
    }
}
