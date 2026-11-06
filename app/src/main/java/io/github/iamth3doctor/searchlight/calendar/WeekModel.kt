package io.github.iamth3doctor.searchlight.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One occurrence of an event, as the Calendar widget shows it. All-day events come from the
 * calendar provider as UTC midnights ([begin] inclusive, [end] exclusive); timed ones are real
 * instants.
 */
class DayEvent(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val calendarName: String,
    val color: Int,
)

/**
 * The Calendar widget's week: which seven days, how the range reads, which events fall on which
 * day, and what each agenda row says about time. Pure; JVM-tested.
 */
object WeekModel {

    /** Up to this many dots under a day's number. */
    const val MAX_DOTS = 3

    /** The first day of the week holding [date], for weeks that start on [firstDay]. */
    fun weekStart(date: LocalDate, firstDay: DayOfWeek): LocalDate =
        date.minusDays(((date.dayOfWeek.value - firstDay.value + 7) % 7).toLong())

    fun days(start: LocalDate): List<LocalDate> = (0L..6L).map { start.plusDays(it) }

    /** java.util / android.icu Calendar's day number (SUNDAY = 1 … SATURDAY = 7) as a DayOfWeek. */
    fun dayOfWeek(calendarDay: Int): DayOfWeek = DayOfWeek.of((calendarDay + 5) % 7 + 1)

    /**
     * "Nov 2 – 8, 2026"; across months "Oct 26 – Nov 1, 2026"; across years
     * "Dec 28, 2026 – Jan 3, 2027".
     */
    fun rangeLabel(start: LocalDate, locale: Locale): String {
        val end = start.plusDays(6)
        val md = DateTimeFormatter.ofPattern("MMM d", locale)
        val d = DateTimeFormatter.ofPattern("d", locale)
        val y = DateTimeFormatter.ofPattern("yyyy", locale)
        return when {
            start.year != end.year -> "${md.format(start)}, ${y.format(start)} – ${md.format(end)}, ${y.format(end)}"
            start.month != end.month -> "${md.format(start)} – ${md.format(end)}, ${y.format(end)}"
            else -> "${md.format(start)} – ${d.format(end)}, ${y.format(end)}"
        }
    }

    /** Days outside the month the week starts in are drawn dimmed. */
    fun dimmed(day: LocalDate, weekStart: LocalDate) = day.year != weekStart.year || day.month != weekStart.month

    /** The first and last local day [e] covers. An event that ends exactly at midnight doesn't reach the next day. */
    fun span(e: DayEvent, zone: ZoneId): Pair<LocalDate, LocalDate> {
        val z = if (e.allDay) ZoneOffset.UTC else zone
        val first = Instant.ofEpochMilli(e.begin).atZone(z).toLocalDate()
        val endMs = if (e.end > e.begin) e.end - 1 else e.begin
        val last = Instant.ofEpochMilli(endMs).atZone(z).toLocalDate()
        return first to maxOf(first, last)
    }

    /**
     * Each of [days] with the events on it: all-day first, then by start time, then title. An
     * event that spans several days is on each of them.
     */
    fun byDay(events: List<DayEvent>, days: List<LocalDate>, zone: ZoneId): Map<LocalDate, List<DayEvent>> {
        val order = compareBy<DayEvent>({ !it.allDay }, { it.begin }, { it.title.lowercase() }, { it.eventId })
        return days.associateWith { day ->
            events.filter { e -> span(e, zone).let { (a, b) -> !day.isBefore(a) && !day.isAfter(b) } }
                .distinctBy { it.eventId to it.begin }
                .sortedWith(order)
        }
    }

    fun dots(count: Int) = count.coerceIn(0, MAX_DOTS)

    /** What an agenda row says about time, on [day]. */
    sealed class When {
        object AllDay : When()
        /** Starts that day, at [ms]. */
        class At(val ms: Long) : When()
        /** Started before that day and ends during it, at [ms]. */
        class Until(val ms: Long) : When()
    }

    fun whenOn(e: DayEvent, day: LocalDate, zone: ZoneId): When {
        if (e.allDay) return When.AllDay
        val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextDay = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return when {
            e.begin >= dayStart -> When.At(e.begin)
            e.end < nextDay -> When.Until(e.end)
            else -> When.AllDay
        }
    }

    /** Milliseconds for the provider query: the week, widened by a day each side (all-day events are in UTC). */
    fun queryRange(start: LocalDate, zone: ZoneId): Pair<Long, Long> =
        start.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            start.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
}
