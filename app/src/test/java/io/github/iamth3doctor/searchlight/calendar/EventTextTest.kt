package io.github.iamth3doctor.searchlight.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class EventTextTest {

    private val us = Locale.US
    private val words = EventText.Words("Every day", "Weekdays", { "Every $it" }, "&")
    private val h12 = { t: LocalTime -> DateTimeFormatter.ofPattern("h:mm a", us).format(t) }

    @Test fun dayAndRanges() {
        assertEquals("Fri Nov 6", EventText.day(LocalDate.of(2026, 11, 6), us))
        assertEquals("8:00–9:00 PM", EventText.range("8:00 PM", "9:00 PM"))
        assertEquals("11:00 AM–1:00 PM", EventText.range("11:00 AM", "1:00 PM"))
        assertEquals("20:00–21:00", EventText.range("20:00", "21:00"))
    }

    @Test fun repeats() {
        assertEquals("Every day", EventText.repeat(Recurrence.DAILY, us, words))
        assertEquals("Weekdays", EventText.repeat(Recurrence(false, Recurrence.WEEKDAYS), us, words))
        assertEquals("Every Monday", EventText.repeat(Recurrence.weekly(MONDAY), us, words))
        assertEquals("Every Mon & Thu", EventText.repeat(Recurrence.weekly(THURSDAY, MONDAY), us, words))
        assertEquals("Every Mon, Wed & Fri", EventText.repeat(Recurrence.weekly(FRIDAY, MONDAY, WEDNESDAY), us, words))
    }

    @Test fun summary() {
        val gym = EventDraft("Gym", LocalDate.of(2026, 11, 6), LocalTime.of(20, 0), LocalTime.of(21, 0))
        assertEquals("Gym, Fri Nov 6, 8:00–9:00 PM", EventText.summary(gym, us, h12))
        assertEquals("Trip, Fri Nov 6", EventText.summary(EventDraft("Trip", LocalDate.of(2026, 11, 6)), us, h12))
    }
}
