package io.github.iamth3doctor.searchlight.calendar

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * How a new event reads in the preview card and the "Saved:" snackbar: "Fri Nov 6",
 * "8:00–9:00 PM", "Every Mon & Thu". Pure; JVM-tested.
 */
object EventText {

    /** "Fri Nov 6". */
    fun day(date: LocalDate, locale: Locale): String = DateTimeFormatter.ofPattern("EEE MMM d", locale).format(date)

    /**
     * Two formatted times as one range, sharing the am/pm: "8:00 PM" + "9:00 PM" ->
     * "8:00–9:00 PM"; "11:00 AM" + "1:00 PM" stays whole; 24-hour times just join.
     */
    fun range(start: String, end: String): String {
        val a = Regex("^(.*?\\d)(\\s*[^\\d\\s][^\\d]*)$").find(start)
        val b = Regex("^(.*?\\d)(\\s*[^\\d\\s][^\\d]*)$").find(end)
        return if (a != null && b != null && a.groupValues[2] == b.groupValues[2]) "${a.groupValues[1]}–$end" else "$start–$end"
    }

    /** Localized words for [repeat]. */
    class Words(val daily: String, val weekdays: String, val every: (String) -> String, val and: String)

    /** "Every day", "Weekdays", "Every Monday", "Every Mon & Thu", "Every Mon, Wed & Fri". */
    fun repeat(r: Recurrence, locale: Locale, words: Words): String = when {
        r.daily -> words.daily
        r.isWeekdays -> words.weekdays
        r.days.size == 1 -> words.every(r.days.first().getDisplayName(TextStyle.FULL, locale))
        else -> {
            val names = r.days.sorted().map { it.getDisplayName(TextStyle.SHORT, locale) }
            words.every(names.dropLast(1).joinToString(", ") + " ${words.and} " + names.last())
        }
    }

    /** "Gym, Fri Nov 6, 8:00–9:00 PM" (all-day: no time). */
    fun summary(d: EventDraft, locale: Locale, time: (java.time.LocalTime) -> String): String {
        val parts = mutableListOf(d.title, day(d.date, locale))
        if (d.timed) parts += range(time(d.start!!), time(d.end ?: d.start.plusMinutes(EventDraft.DEFAULT_MINUTES)))
        return parts.joinToString(", ")
    }
}
