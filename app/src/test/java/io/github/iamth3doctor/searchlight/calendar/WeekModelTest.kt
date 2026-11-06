package io.github.iamth3doctor.searchlight.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class WeekModelTest {

    private val ny = ZoneId.of("America/New_York")

    private fun ms(date: LocalDate, h: Int, m: Int = 0, zone: ZoneId = ny) = date.atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun utc(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun timed(id: Long, title: String, begin: Long, end: Long) = DayEvent(id, title, begin, end, false, "Work", 0)
    private fun allDay(id: Long, title: String, first: LocalDate, daysLong: Long = 1) =
        DayEvent(id, title, utc(first), utc(first.plusDays(daysLong)), true, "Home", 0)

    @Test fun weekStartsOnTheSystemsFirstDay() {
        val thu = LocalDate.of(2026, 11, 5)
        assertEquals(LocalDate.of(2026, 11, 1), WeekModel.weekStart(thu, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 11, 2), WeekModel.weekStart(thu, DayOfWeek.MONDAY))
        assertEquals(LocalDate.of(2026, 10, 31), WeekModel.weekStart(thu, DayOfWeek.SATURDAY))
        // The first day itself starts its own week.
        assertEquals(thu, WeekModel.weekStart(thu, DayOfWeek.THURSDAY))
        assertEquals(7, WeekModel.days(thu).size)
        assertEquals(LocalDate.of(2026, 11, 11), WeekModel.days(thu).last())
    }

    @Test fun calendarDayNumbersMapToDaysOfWeek() {
        assertEquals(DayOfWeek.SUNDAY, WeekModel.dayOfWeek(1))
        assertEquals(DayOfWeek.MONDAY, WeekModel.dayOfWeek(2))
        assertEquals(DayOfWeek.SATURDAY, WeekModel.dayOfWeek(7))
    }

    @Test fun rangeLabels() {
        assertEquals("Nov 2 – 8, 2026", WeekModel.rangeLabel(LocalDate.of(2026, 11, 2), Locale.US))
        assertEquals("Oct 26 – Nov 1, 2026", WeekModel.rangeLabel(LocalDate.of(2026, 10, 26), Locale.US))
        assertEquals("Dec 28, 2026 – Jan 3, 2027", WeekModel.rangeLabel(LocalDate.of(2026, 12, 28), Locale.US))
    }

    @Test fun daysOutsideTheStartMonthAreDimmed() {
        val start = LocalDate.of(2026, 10, 26)
        val dimmed = WeekModel.days(start).map { WeekModel.dimmed(it, start) }
        assertEquals(listOf(false, false, false, false, false, false, true), dimmed)
        assertTrue(WeekModel.days(LocalDate.of(2026, 11, 2)).none { WeekModel.dimmed(it, LocalDate.of(2026, 11, 2)) })
    }

    @Test fun eventsLandOnTheirDaysInOrder() {
        val start = LocalDate.of(2026, 11, 2)
        val days = WeekModel.days(start)
        val wed = LocalDate.of(2026, 11, 4)
        val late = timed(1, "Late", ms(wed, 18), ms(wed, 19))
        val early = timed(2, "Early", ms(wed, 9), ms(wed, 10))
        val holiday = allDay(3, "Holiday", wed)
        val other = timed(4, "Next week", ms(start.plusDays(8), 9), ms(start.plusDays(8), 10))
        val by = WeekModel.byDay(listOf(late, early, holiday, other), days, ny)
        assertEquals(listOf("Holiday", "Early", "Late"), by.getValue(wed).map { it.title })
        // Only days with events have any; the next week's event is on none of these.
        assertEquals(listOf(wed), by.filterValues { it.isNotEmpty() }.keys.toList())
    }

    @Test fun allDayEventsUseTheirOwnDatesInEveryZone() {
        val sat = LocalDate.of(2026, 11, 7)
        val days = WeekModel.days(LocalDate.of(2026, 11, 2))
        val e = allDay(1, "Trip", sat)
        // UTC midnight is still Friday evening in New York: it's on Saturday regardless.
        for (zone in listOf(ny, ZoneId.of("Asia/Tokyo"), ZoneOffset.UTC)) {
            val by = WeekModel.byDay(listOf(e), days, zone)
            assertEquals(zone.toString(), listOf(sat), by.filterValues { it.isNotEmpty() }.keys.toList())
        }
        // Three days long: Sat, Sun, Mon (only Sat, Sun in this week).
        val long = allDay(2, "Long", sat, 3)
        assertEquals(sat to sat.plusDays(2), WeekModel.span(long, ny))
    }

    @Test fun multiDayAndMidnightEvents() {
        val mon = LocalDate.of(2026, 11, 2)
        val days = WeekModel.days(mon)
        val overnight = timed(1, "Overnight", ms(mon, 22), ms(mon.plusDays(1), 2))
        val toMidnight = timed(2, "To midnight", ms(mon, 20), ms(mon.plusDays(1), 0))
        val zeroLength = timed(3, "Reminder", ms(mon, 8), ms(mon, 8))
        val by = WeekModel.byDay(listOf(overnight, toMidnight, zeroLength), days, ny)
        assertEquals(listOf("Reminder", "To midnight", "Overnight"), by.getValue(mon).map { it.title })
        assertEquals(listOf("Overnight"), by.getValue(mon.plusDays(1)).map { it.title })
    }

    @Test fun whatEachRowSaysAboutTime() {
        val mon = LocalDate.of(2026, 11, 2)
        val tue = mon.plusDays(1)
        val wed = mon.plusDays(2)
        val e = timed(1, "Conference", ms(mon, 15), ms(wed, 11))
        assertEquals(ms(mon, 15), (WeekModel.whenOn(e, mon, ny) as WeekModel.When.At).ms)
        assertTrue(WeekModel.whenOn(e, tue, ny) is WeekModel.When.AllDay)
        assertEquals(ms(wed, 11), (WeekModel.whenOn(e, wed, ny) as WeekModel.When.Until).ms)
        assertTrue(WeekModel.whenOn(allDay(2, "Day off", mon), mon, ny) is WeekModel.When.AllDay)
    }

    @Test fun dotsCapAtThree() {
        assertEquals(0, WeekModel.dots(0))
        assertEquals(2, WeekModel.dots(2))
        assertEquals(3, WeekModel.dots(3))
        assertEquals(3, WeekModel.dots(9))
    }

    @Test fun theQueryCoversTheWeekAndADayEachSide() {
        val start = LocalDate.of(2026, 11, 2)
        val (from, to) = WeekModel.queryRange(start, ny)
        assertEquals(ms(start.minusDays(1), 0), from)
        assertEquals(ms(start.plusDays(8), 0), to)
        assertFalse(from >= to)
    }
}
