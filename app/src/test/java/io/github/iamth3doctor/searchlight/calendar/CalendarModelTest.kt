package io.github.iamth3doctor.searchlight.calendar

import io.github.iamth3doctor.searchlight.calendar.CalendarModel.Rel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class CalendarModelTest {

    private val ny = ZoneId.of("America/New_York")

    private fun ms(date: LocalDate, h: Int, m: Int = 0, zone: ZoneId = ny) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun utc(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun timed(id: Long, title: String, begin: Long, end: Long) = DayEvent(id, title, begin, end, false, "Work", 0)
    private fun allDay(id: Long, title: String, first: LocalDate, daysLong: Long = 1) =
        DayEvent(id, title, utc(first), utc(first.plusDays(daysLong)), true, "Home", 0)

    // ---- weeks and months ----

    @Test fun weekStartsOnTheSystemsFirstDay() {
        val thu = LocalDate.of(2026, 11, 5)
        assertEquals(LocalDate.of(2026, 11, 1), CalendarModel.weekStart(thu, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 11, 2), CalendarModel.weekStart(thu, DayOfWeek.MONDAY))
        assertEquals(thu, CalendarModel.weekStart(thu, DayOfWeek.THURSDAY))
        assertEquals(DayOfWeek.SUNDAY, CalendarModel.dayOfWeek(1))
        assertEquals(DayOfWeek.MONDAY, CalendarModel.dayOfWeek(2))
        assertEquals(DayOfWeek.SATURDAY, CalendarModel.dayOfWeek(7))
        assertEquals(DayOfWeek.MONDAY, CalendarModel.weekdays(DayOfWeek.MONDAY).first())
        assertEquals(DayOfWeek.SUNDAY, CalendarModel.weekdays(DayOfWeek.MONDAY).last())
    }

    @Test fun monthGridIsWholeWeeks() {
        // June 2026 starts on a Monday: Sunday-first weeks run May 31 – Jul 4 (5 weeks).
        val june = CalendarModel.monthGrid(YearMonth.of(2026, 6), DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 5, 31), june.first())
        assertEquals(LocalDate.of(2026, 7, 4), june.last())
        assertEquals(35, june.size)
        // Monday-first, the 1st opens the grid with no leading days.
        assertEquals(LocalDate.of(2026, 6, 1), CalendarModel.monthGrid(YearMonth.of(2026, 6), DayOfWeek.MONDAY).first())
        // August 2026 (starts Saturday, 31 days) needs six Sunday-first weeks.
        assertEquals(42, CalendarModel.monthGrid(YearMonth.of(2026, 8), DayOfWeek.SUNDAY).size)
        // February 2026 (starts Sunday, 28 days) fits exactly four.
        assertEquals(28, CalendarModel.monthGrid(YearMonth.of(2026, 2), DayOfWeek.SUNDAY).size)
        for (g in listOf(june)) assertEquals(0, g.size % 7)
        assertTrue(CalendarModel.dimmed(LocalDate.of(2026, 5, 31), YearMonth.of(2026, 6)))
        assertFalse(CalendarModel.dimmed(LocalDate.of(2026, 6, 30), YearMonth.of(2026, 6)))
    }

    @Test fun labels() {
        assertEquals("June 2026", CalendarModel.monthLabel(YearMonth.of(2026, 6), Locale.US))
        assertEquals("Fri, Jun 5", CalendarModel.dayLabel(LocalDate.of(2026, 6, 5), Locale.US))
        val start = LocalDate.of(2026, 5, 31)
        val (from, to) = CalendarModel.queryRange(start, start.plusDays(34), ny)
        assertEquals(ms(start.minusDays(1), 0), from)
        assertEquals(ms(start.plusDays(36), 0), to)
    }

    // ---- events on days ----

    @Test fun eventsLandOnTheirDaysInOrder() {
        val wed = LocalDate.of(2026, 11, 4)
        val days = CalendarModel.monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        val by = CalendarModel.byDay(listOf(
            timed(1, "Late", ms(wed, 18), ms(wed, 19)),
            timed(2, "Early", ms(wed, 9), ms(wed, 10)),
            allDay(3, "Holiday", wed),
        ), days, ny)
        assertEquals(listOf("Holiday", "Early", "Late"), by.getValue(wed).map { it.title })
        assertEquals(listOf(wed), by.filterValues { it.isNotEmpty() }.keys.toList())
    }

    @Test fun allDayEventsUseTheirOwnDatesInEveryZone() {
        val sat = LocalDate.of(2026, 11, 7)
        val days = CalendarModel.monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        for (zone in listOf(ny, ZoneId.of("Asia/Tokyo"), ZoneOffset.UTC)) {
            val by = CalendarModel.byDay(listOf(allDay(1, "Trip", sat)), days, zone)
            assertEquals(zone.toString(), listOf(sat), by.filterValues { it.isNotEmpty() }.keys.toList())
        }
        assertEquals(sat to sat.plusDays(2), CalendarModel.span(allDay(2, "Long", sat, 3), ny))
    }

    @Test fun midnightAndOvernight() {
        val mon = LocalDate.of(2026, 11, 2)
        val days = CalendarModel.monthGrid(YearMonth.of(2026, 11), DayOfWeek.SUNDAY)
        val by = CalendarModel.byDay(listOf(
            timed(1, "Overnight", ms(mon, 22), ms(mon.plusDays(1), 2)),
            timed(2, "To midnight", ms(mon, 20), ms(mon.plusDays(1), 0)),
        ), days, ny)
        assertEquals(listOf("To midnight", "Overnight"), by.getValue(mon).map { it.title })
        assertEquals(listOf("Overnight"), by.getValue(mon.plusDays(1)).map { it.title })
        assertEquals(3, CalendarModel.dots(7))
        assertEquals(0, CalendarModel.dots(0))
    }

    // ---- live labels ----

    private val day = LocalDate.of(2026, 6, 5)
    private val now = ms(day, 14, 5) // Fri Jun 5, 2:05 PM

    private fun rel(e: DayEvent, at: Long = now) = CalendarModel.relative(e, at, ny)

    @Test fun happeningNowCountsDownTheRest() {
        val e = timed(1, "Standup", ms(day, 14, 0), ms(day, 14, 30))
        assertEquals(Rel.Now(25), rel(e))
        // Rounded up: with 30 s to go it's still "1 min left", and it's over at the end.
        assertEquals(Rel.Now(1), rel(e, ms(day, 14, 29) + 30_000))
        assertEquals(Rel.Ended, rel(e, ms(day, 14, 30)))
        assertTrue(CalendarModel.ended(e, ms(day, 15, 0), ny))
        assertFalse(CalendarModel.ended(e, now, ny))
    }

    @Test fun laterTodayInMinutesThenHours() {
        assertEquals(Rel.InMinutes(35), rel(timed(1, "Call", ms(day, 14, 40), ms(day, 15, 0))))
        assertEquals(Rel.InMinutes(1), rel(timed(2, "Soon", now + 10_000, now + 600_000)))
        assertEquals(Rel.InHours(1), rel(timed(3, "Hour", ms(day, 15, 5), ms(day, 16, 0))))
        assertEquals(Rel.InHours(2), rel(timed(4, "Later", ms(day, 16, 50), ms(day, 17, 0))))
        assertEquals(Rel.InHours(9), rel(timed(5, "Night", ms(day, 23, 30), ms(day, 23, 45))))
    }

    @Test fun otherDays() {
        assertEquals(Rel.Tomorrow, rel(timed(1, "Flight", ms(day.plusDays(1), 7), ms(day.plusDays(1), 9))))
        // Just after midnight tomorrow is still "Tomorrow", not "In 10 hr.".
        assertEquals(Rel.Tomorrow, rel(timed(2, "Early", ms(day.plusDays(1), 0, 15), ms(day.plusDays(1), 1))))
        assertEquals(Rel.InDays(4), rel(timed(3, "Dentist", ms(day.plusDays(4), 9), ms(day.plusDays(4), 10))))
        assertEquals(Rel.Ended, rel(timed(4, "Yesterday", ms(day.minusDays(1), 9), ms(day.minusDays(1), 10))))
        assertEquals(Rel.Ended, rel(timed(5, "This morning", ms(day, 9), ms(day, 10))))
    }

    @Test fun allDayLabels() {
        assertEquals(Rel.AllDay(Rel.Today), rel(allDay(1, "Holiday", day)))
        assertEquals(Rel.AllDay(Rel.Tomorrow), rel(allDay(2, "Trip", day.plusDays(1))))
        assertEquals(Rel.AllDay(Rel.InDays(4)), rel(allDay(3, "Conference", day.plusDays(4))))
        // A three-day trip that started yesterday is today's; one that ended yesterday is over.
        assertEquals(Rel.AllDay(Rel.Today), rel(allDay(4, "Long", day.minusDays(1), 3)))
        assertEquals(Rel.Ended, rel(allDay(5, "Done", day.minusDays(1))))
    }

    @Test fun timeRangeAndDetail() {
        val fmt = { t: Long -> java.time.Instant.ofEpochMilli(t).atZone(ny).toLocalTime().toString() }
        assertEquals("14:00 – 14:30", CalendarModel.timeRange(timed(1, "x", ms(day, 14), ms(day, 14, 30)), fmt))
        assertEquals("14:00", CalendarModel.timeRange(timed(2, "x", ms(day, 14), ms(day, 14)), fmt))
        assertNull(CalendarModel.timeRange(allDay(3, "x", day), fmt))
        assertEquals("Gate B12", CalendarModel.detail("  Gate B12 ", "UA 123"))
        assertEquals("UA 123 SFO → JFK", CalendarModel.detail("", "\n  UA 123 SFO → JFK\nSeat 14C"))
        assertEquals("", CalendarModel.detail(null, null))
    }
}
