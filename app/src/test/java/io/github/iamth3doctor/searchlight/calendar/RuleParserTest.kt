package io.github.iamth3doctor.searchlight.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class RuleParserTest {

    private val fri = LocalDate.of(2026, 11, 6) // a Friday
    private fun p(text: String, default: LocalDate = fri) = RuleParser.parse(text, fri, default)
    private fun t(h: Int, m: Int = 0) = LocalTime.of(h, m)

    @Test fun theExamples() {
        val gym = p("Gym tomorrow 8-9pm")!!
        assertEquals("Gym", gym.title)
        assertEquals(fri.plusDays(1), gym.date)
        assertEquals(t(20) to t(21), gym.start to gym.end)
        assertFalse(gym.allDay)
        assertNull(gym.recurrence)

        val dinner = p("Dinner Fri 7pm")!!
        assertEquals("Dinner", dinner.title)
        assertEquals(fri, dinner.date) // today is Friday
        assertEquals(t(19) to t(20), dinner.start to dinner.end) // an hour by default

        val lunch = p("Lunch with Blake Tue 12pm")!!
        assertEquals("Lunch with Blake", lunch.title)
        assertEquals(LocalDate.of(2026, 11, 10), lunch.date)
        assertEquals(t(12), lunch.start)

        val yoga = p("Yoga every Mon/Thu 7am")!!
        assertEquals("Yoga", yoga.title)
        assertEquals(setOf(MONDAY, THURSDAY), yoga.recurrence!!.days)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TH", yoga.recurrence!!.rrule)
        assertEquals(LocalDate.of(2026, 11, 9), yoga.date) // the first Monday from today
        assertEquals(t(7) to t(8), yoga.start to yoga.end)
    }

    @Test fun datesAndWeekdays() {
        assertEquals(fri, p("Call mom today")!!.date)
        assertEquals(fri, p("Movie tonight 9pm")!!.date)
        assertEquals(LocalDate.of(2026, 11, 9), p("Standup monday 9:30am")!!.date)
        assertEquals(LocalDate.of(2026, 11, 13), p("Review next fri 2pm")!!.date)
        assertEquals(LocalDate.of(2026, 11, 12), p("Dentist Nov 12 10am")!!.date)
        assertEquals(LocalDate.of(2026, 12, 3), p("Party 3rd December")!!.date)
        assertEquals(LocalDate.of(2027, 1, 5), p("Taxes Jan 5")!!.date) // past this year: next year
    }

    @Test fun timesRangesAndDurations() {
        assertEquals(t(14, 30) to t(15, 30), p("Meeting 14:30")!!.let { it.start to it.end })
        assertEquals(t(11) to t(13), p("Brunch sat 11-1pm")!!.let { it.start to it.end })
        assertEquals(t(14) to t(15, 30), p("Workshop 2pm to 3:30pm")!!.let { it.start to it.end })
        assertEquals(t(9) to t(10, 30), p("Run 9am for 90 min")!!.let { it.start to it.end })
        assertEquals(t(18) to t(20), p("Class 6pm for 2 hours")!!.let { it.start to it.end })
        assertEquals(t(12) to t(13), p("Lunch noon")!!.let { it.start to it.end })
        val late = p("Party 10pm-1am")!!
        assertEquals(t(22) to t(1), late.start to late.end)
        assertTrue(late.endsNextDay)
        assertEquals("Party", late.title)
    }

    @Test fun noTimeIsAllDayAndTheDefaultDateApplies() {
        val trip = p("Trip to Paris")!!
        assertTrue(trip.allDay)
        assertEquals(fri, trip.date)
        // A day tapped first: the event goes there unless the text names another.
        val tapped = LocalDate.of(2026, 11, 20)
        assertEquals(tapped, p("Haircut 3pm", tapped)!!.date)
        assertEquals(fri.plusDays(1), p("Haircut tomorrow 3pm", tapped)!!.date)
    }

    @Test fun repeats() {
        val wd = p("Standup weekdays 9am")!!
        assertTrue(wd.recurrence!!.isWeekdays)
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", wd.recurrence!!.rrule)
        assertEquals(LocalDate.of(2026, 11, 6), wd.date) // Friday is a weekday
        assertEquals("FREQ=DAILY", p("Meditate every day 7am")!!.recurrence!!.rrule)
        assertEquals(setOf(TUESDAY), p("Piano every Tuesday 5pm")!!.recurrence!!.days)
        assertEquals(setOf(FRIDAY), p("Team lunch weekly fri 12pm")!!.recurrence!!.days)
        assertEquals("Piano", p("Piano every Tuesday 5pm")!!.title)
    }

    @Test fun notUnderstood() {
        assertNull(p(""))
        assertNull(p("   "))
        assertNull(p("tomorrow 8pm"))
        assertNull(p("at 7"))
    }

    @Test fun draftTimes() {
        val ny = ZoneId.of("America/New_York")
        val gym = p("Gym tomorrow 8-9pm")!!
        assertEquals(60L * 60_000, gym.endMs(ny) - gym.beginMs(ny))
        assertEquals("PT60M", gym.duration(ny))
        val late = p("Party 10pm-1am")!!
        assertEquals(3 * 60L * 60_000, late.endMs(ny) - late.beginMs(ny))
        val allDay = p("Trip")!!
        assertEquals(86_400_000L, allDay.endMs(ny) - allDay.beginMs(ny))
        assertEquals("P1D", allDay.duration(ny))
    }
}
