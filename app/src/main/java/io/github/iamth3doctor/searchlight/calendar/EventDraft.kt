package io.github.iamth3doctor.searchlight.calendar

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** How an event repeats: every day, or weekly on [days]. */
data class Recurrence(val daily: Boolean, val days: Set<DayOfWeek> = emptySet()) {

    /** The RFC 5545 rule the Calendar Provider stores ("FREQ=WEEKLY;BYDAY=MO,TH"). */
    val rrule: String
        get() = if (daily) "FREQ=DAILY"
        else "FREQ=WEEKLY;BYDAY=" + days.sorted().joinToString(",") { CODES.getValue(it) }

    val isWeekdays get() = !daily && days == WEEKDAYS

    companion object {
        val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        val DAILY = Recurrence(daily = true)
        fun weekly(vararg d: DayOfWeek) = Recurrence(daily = false, days = d.toSet())
        val CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE", DayOfWeek.THURSDAY to "TH",
            DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA", DayOfWeek.SUNDAY to "SU",
        )
        fun dayOf(code: String): DayOfWeek? = CODES.entries.firstOrNull { it.value.equals(code.trim(), ignoreCase = true) }?.key
    }
}

/**
 * An event to be saved, as parsed from what was typed (or edited in the sheet). [start] null or
 * [allDay] true: an all-day event on [date]. A timed event ends at [end] on [date], or the next
 * day when [end] isn't after [start] ("11pm–1am"). Pure; JVM-tested.
 */
data class EventDraft(
    val title: String,
    val date: LocalDate,
    val start: LocalTime? = null,
    val end: LocalTime? = null,
    val allDay: Boolean = start == null,
    val recurrence: Recurrence? = null,
    /** The calendar to save on (-1: the configured default). */
    val calendarId: Long = -1,
    /** Minutes before the start for a reminder (null: none). */
    val reminderMinutes: Int? = null,
    /** Where it is (kept through edits and moves). */
    val location: String = "",
    /** The provider's event this edits (-1: a new event). */
    val eventId: Long = -1,
    /** A repeat rule the sheet can't show (e.g. monthly), kept as it was through an edit. */
    val otherRule: String? = null,
) {
    val timed get() = !allDay && start != null

    val repeats get() = recurrence != null || otherRule != null

    /** Ends on the next day ("11pm–1am"). */
    val endsNextDay get() = timed && end != null && !end.isAfter(start)

    /** The series' first day: the first of its weekdays on or after [date] (a one-off: [date]). */
    fun firstDay(): LocalDate {
        val r = recurrence ?: return date
        if (r.daily || r.days.isEmpty()) return date
        return generateSequence(date) { it.plusDays(1) }.take(7).first { it.dayOfWeek in r.days }
    }

    /** Start and end in ms. All-day: UTC midnights, as the provider stores them. */
    fun beginMs(zone: ZoneId): Long {
        val d = firstDay()
        return if (timed) d.atTime(start).atZone(zone).toInstant().toEpochMilli()
        else d.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    fun endMs(zone: ZoneId): Long {
        val d = firstDay()
        if (!timed) return d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val e = end ?: start!!.plusHours(1)
        val endDay = if (!e.isAfter(start)) d.plusDays(1) else d
        return endDay.atTime(e).atZone(zone).toInstant().toEpochMilli()
    }

    /** RFC 5545 duration for a repeating event ("PT60M", "P1D"); the provider needs it instead of an end. */
    fun duration(zone: ZoneId): String =
        if (!timed) "P1D" else "PT${Duration.ofMillis(endMs(zone) - beginMs(zone)).toMinutes()}M"

    companion object {
        const val DEFAULT_MINUTES = 60L
    }
}
