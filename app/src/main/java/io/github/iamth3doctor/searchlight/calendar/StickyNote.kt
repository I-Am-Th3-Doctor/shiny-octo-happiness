package io.github.iamth3doctor.searchlight.calendar

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What the Sticky note widget writes: today's date in marker ("WED NOV 18"), then today's events
 * in pen, in time order (all-day first), at most [MAX_LINES] of them with "+N more" under. The
 * next event (the one on now, else the next to start) is in a different ink; ones already over
 * are checked off. Pure; JVM-tested.
 */
object StickyNote {

    const val MAX_LINES = 3

    /** Checked-off lines are written in the same ink, faded this much (still easy to read). */
    const val DONE_ALPHA = 0.75f

    enum class Ink {
        /** Over: checked off and struck through. */
        DONE,
        /** On now, or the next to start: the highlight ink, underlined. */
        NEXT,
        PLAIN,
    }

    /** One event line: "9:00 AM — Team standup" ([time] is null for an all-day event). */
    class Line(val event: DayEvent, val time: String?, val ink: Ink)

    class Note(val day: LocalDate, val dateLabel: String, val lines: List<Line>, val more: Int) {
        val empty: Boolean get() = lines.isEmpty()
    }

    /** The paper colours on offer (Settings). [ink] is the pen and marker, [highlight] the next event's ink. */
    enum class Paper(val key: String, val top: Int, val bottom: Int, val curl: Int, val ink: Int, val highlight: Int) {
        YELLOW("yellow", 0xFFFFF29A.toInt(), 0xFFFBE26A.toInt(), 0xFFE9CC4E.toInt(), 0xFF262320.toInt(), 0xFFB71C1C.toInt()),
        PINK("pink", 0xFFFFD3E2.toInt(), 0xFFFFBCD3.toInt(), 0xFFEFA2BD.toInt(), 0xFF2A2024.toInt(), 0xFF1F4FB8.toInt()),
        BLUE("blue", 0xFFD2ECFF.toInt(), 0xFFB8E0FF.toInt(), 0xFF97CBF0.toInt(), 0xFF1E2430.toInt(), 0xFFB71C1C.toInt()),
        GREEN("green", 0xFFDDF6C4.toInt(), 0xFFC8EFA6.toInt(), 0xFFA9DA84.toInt(), 0xFF1F2A1C.toInt(), 0xFFB71C1C.toInt());

        companion object {
            val DEFAULT = YELLOW
            fun of(key: String?) = entries.firstOrNull { it.key == key } ?: DEFAULT
        }
    }

    /** "WED NOV 18". */
    fun dateLabel(day: LocalDate, locale: Locale): String =
        DateTimeFormatter.ofPattern("EEE MMM d", locale).format(day).replace(".", "").uppercase(locale)

    /** Today's events (the day [nowMs] is in), in time order: all-day first, then by start, then title. */
    fun todays(events: List<DayEvent>, nowMs: Long, zone: ZoneId): List<DayEvent> {
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        return CalendarModel.byDay(events, listOf(day), zone)[day].orEmpty()
    }

    /** The timed event on now, else the next to start today; null when none is left. */
    fun next(today: List<DayEvent>, nowMs: Long): DayEvent? =
        today.filter { !it.allDay && nowMs < maxOf(it.begin, it.end) }.minByOrNull { it.begin }

    /** A timed event that's over. (An all-day one lasts all day: never checked off on its day.) */
    fun done(e: DayEvent, nowMs: Long): Boolean = !e.allDay && nowMs >= maxOf(e.begin, e.end)

    /** The note for the day [nowMs] is in; [time] formats a start time ("9:00 AM"). */
    fun note(events: List<DayEvent>, nowMs: Long, zone: ZoneId, locale: Locale, time: (Long) -> String): Note {
        val day = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val today = todays(events, nowMs, zone)
        val next = next(today, nowMs)
        val lines = today.take(MAX_LINES).map { e ->
            val ink = when {
                e === next -> Ink.NEXT
                done(e, nowMs) -> Ink.DONE
                else -> Ink.PLAIN
            }
            Line(e, if (e.allDay) null else time(e.begin), ink)
        }
        return Note(day, dateLabel(day, locale), lines, (today.size - MAX_LINES).coerceAtLeast(0))
    }

    /** "9:00 AM — Team standup"; an all-day event is "[allDay] — Holiday". */
    fun lineText(line: Line, allDay: String, untitled: String): String =
        "${line.time ?: allDay} — ${line.event.title.ifBlank { untitled }}"
}
