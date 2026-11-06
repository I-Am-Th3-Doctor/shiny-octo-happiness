package io.github.iamth3doctor.searchlight.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDate
import java.time.LocalTime

class AiEventJsonTest {

    private fun json(title: String = "Gym", date: String = "2026-11-07", allDay: Boolean = false, start: String = "20:00",
                     end: String = "21:00", repeat: String = "none", days: String = "", understood: Boolean = true) =
        """{"understood":$understood,"title":"$title","date":"$date","all_day":$allDay,"start":"$start","end":"$end","repeat":"$repeat","days":[$days]}"""

    @Test fun aTimedEvent() {
        val d = AiEventJson.toDraft(json())!!
        assertEquals("Gym", d.title)
        assertEquals(LocalDate.of(2026, 11, 7), d.date)
        assertEquals(LocalTime.of(20, 0) to LocalTime.of(21, 0), d.start to d.end)
        assertNull(d.recurrence)
    }

    @Test fun defaultsAndRepeats() {
        // No end: an hour.
        assertEquals(LocalTime.of(20, 0), AiEventJson.toDraft(json(start = "19:00", end = ""))!!.end)
        // No start: all day.
        assertTrue(AiEventJson.toDraft(json(start = "", end = ""))!!.allDay)
        // Weekly Mon/Thu from a Friday: starts the next Monday.
        val yoga = AiEventJson.toDraft(json(title = "Yoga", date = "2026-11-06", start = "7:00", end = "", repeat = "weekly", days = "\"MO\",\"TH\""))!!
        assertEquals(setOf(MONDAY, THURSDAY), yoga.recurrence!!.days)
        assertEquals(LocalDate.of(2026, 11, 9), yoga.date)
        assertEquals(LocalTime.of(7, 0), yoga.start)
        assertEquals("FREQ=DAILY", AiEventJson.toDraft(json(repeat = "daily"))!!.recurrence!!.rrule)
    }

    @Test fun anythingOffMeansNull() {
        assertNull(AiEventJson.toDraft(json(understood = false)))
        assertNull(AiEventJson.toDraft(json(title = " ")))
        assertNull(AiEventJson.toDraft(json(date = "Friday")))
        assertNull(AiEventJson.toDraft("not json"))
        assertNull(AiEventJson.toDraft("{}"))
    }
}
