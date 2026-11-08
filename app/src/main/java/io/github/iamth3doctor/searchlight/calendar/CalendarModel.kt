package io.github.iamth3doctor.searchlight.calendar

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * One occurrence of an event, as the Calendar widget shows it. All-day events come from the
 * calendar provider as UTC midnights ([begin] inclusive, [end] exclusive); timed ones are real
 * instants. [detail] is the second line: the location, else the first line of the description.
 */
class DayEvent(
    val eventId: Long,
    val title: String,
    val begin: Long,
    val end: Long,
    val allDay: Boolean,
    val calendarName: String,
    val color: Int,
    val detail: String = "",
    /** The calendar it's on (-1: unknown). */
    val calendarId: Long = -1,
    /** The calendar's access level (CalendarContract.Calendars.CAL_ACCESS_*): below contributor it's read-only. */
    val accessLevel: Int = 700,
    /** One occurrence of a repeating event. */
    val recurring: Boolean = false,
    /** Where it is (the detail view shows it). */
    val location: String = "",
    /** For an occurrence changed on its own (an exception row): its series' event id, else -1. */
    val originalId: Long = -1,
    /** The full description (the Weather tile's "Next up" looks for a video-call link in it). */
    val description: String = "",
)

/**
 * The Calendar widget's month grid, which events fall on which day, and each event's live
 * relative label ("now · 25 min left", "In 35 min.", "Tomorrow", "Ended"…). Pure; JVM-tested.
 */
object CalendarModel {

    /** Up to this many dots under a day's number. */
    const val MAX_DOTS = 3

    // ------------------------------------------------------------------ weeks and months

    /** The first day of the week holding [date], for weeks that start on [firstDay]. */
    fun weekStart(date: LocalDate, firstDay: DayOfWeek): LocalDate =
        date.minusDays(((date.dayOfWeek.value - firstDay.value + 7) % 7).toLong())

    /** java.util / android.icu Calendar's day number (SUNDAY = 1 … SATURDAY = 7) as a DayOfWeek. */
    fun dayOfWeek(calendarDay: Int): DayOfWeek = DayOfWeek.of((calendarDay + 5) % 7 + 1)

    /** The seven weekdays in header order, starting at [firstDay]. */
    fun weekdays(firstDay: DayOfWeek): List<DayOfWeek> = (0L..6L).map { firstDay.plus(it) }

    /**
     * Every day the month grid shows: whole weeks from the one holding the 1st to the one holding
     * the last day (four to six weeks), so leading and trailing days of the next months fill it.
     */
    fun monthGrid(month: YearMonth, firstDay: DayOfWeek): List<LocalDate> {
        val start = weekStart(month.atDay(1), firstDay)
        val end = weekStart(month.atEndOfMonth(), firstDay).plusDays(6)
        return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.toList()
    }

    /** Days outside the shown month are drawn dimmed. */
    fun dimmed(day: LocalDate, month: YearMonth) = YearMonth.from(day) != month

    /** "June 2026". */
    fun monthLabel(month: YearMonth, locale: Locale): String =
        DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(month).replaceFirstChar { it.titlecase(locale) }

    /** "Fri, Jun 5". */
    fun dayLabel(day: LocalDate, locale: Locale): String = DateTimeFormatter.ofPattern("EEE, MMM d", locale).format(day)

    /** Milliseconds for the provider query: the grid's days, widened by a day each side (all-day events are in UTC). */
    fun queryRange(first: LocalDate, last: LocalDate, zone: ZoneId): Pair<Long, Long> =
        first.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            last.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()

    // ------------------------------------------------------------------ events on days

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

    // ------------------------------------------------------------------ live labels

    /** An event's place in time, relative to now (the agenda's right-hand label). */
    sealed class Rel {
        /** Happening now, ending in [minutesLeft] (rounded up). */
        data class Now(val minutesLeft: Long) : Rel()
        /** Later today, under an hour away (rounded up: never "In 0 min."). */
        data class InMinutes(val minutes: Long) : Rel()
        /** Later today, an hour or more away (whole hours). */
        data class InHours(val hours: Long) : Rel()
        object Tomorrow : Rel()
        /** Two or more days away. */
        data class InDays(val days: Long) : Rel()
        object Ended : Rel()
        /** An all-day event and its day: [day] is [Today], [Tomorrow] or [InDays] (an over one is [Ended]). */
        data class AllDay(val day: Rel) : Rel()
        object Today : Rel()
    }

    fun relative(e: DayEvent, nowMs: Long, zone: ZoneId): Rel {
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        fun inDays(first: LocalDate): Rel = when (val n = ChronoUnit.DAYS.between(today, first)) {
            1L -> Rel.Tomorrow
            else -> Rel.InDays(n)
        }
        if (e.allDay) {
            val (first, last) = span(e, zone)
            return when {
                last.isBefore(today) -> Rel.Ended
                !first.isAfter(today) -> Rel.AllDay(Rel.Today)
                else -> Rel.AllDay(inDays(first))
            }
        }
        if (nowMs >= maxOf(e.begin, e.end)) return Rel.Ended
        if (nowMs >= e.begin) return Rel.Now(ceilMinutes(e.end - nowMs))
        val startDay = Instant.ofEpochMilli(e.begin).atZone(zone).toLocalDate()
        if (startDay != today) return inDays(startDay)
        val minutes = ceilMinutes(e.begin - nowMs)
        return if (minutes < 60) Rel.InMinutes(minutes) else Rel.InHours((e.begin - nowMs) / 3_600_000L)
    }

    private fun ceilMinutes(ms: Long) = ((ms + 59_999L) / 60_000L).coerceAtLeast(1)

    /** Over by [nowMs]: drawn dimmed. */
    fun ended(e: DayEvent, nowMs: Long, zone: ZoneId) = relative(e, nowMs, zone) == Rel.Ended

    /** "2:00 PM – 2:30 PM" with [time] formatting each end; null for all-day events. */
    fun timeRange(e: DayEvent, time: (Long) -> String): String? =
        if (e.allDay) null else if (e.end <= e.begin) time(e.begin) else "${time(e.begin)} – ${time(e.end)}"

    /** The detail line from a location and a description: the location, else the description's first line. */
    fun detail(location: String?, description: String?): String =
        location?.trim()?.takeIf { it.isNotEmpty() }
            ?: description?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(DETAIL_MAX).orEmpty()

    private const val DETAIL_MAX = 80
}
