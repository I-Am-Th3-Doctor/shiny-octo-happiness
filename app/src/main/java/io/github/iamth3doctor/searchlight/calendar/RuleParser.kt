package io.github.iamth3doctor.searchlight.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * On-device parsing of a typed event ("Gym tomorrow 8-9pm", "Yoga every Mon/Thu 7am", "Dinner
 * Fri 7pm"), used without an AI key or when the AI can't be reached. Understands today /
 * tonight / tomorrow, weekday names, "Nov 12", times (8pm, 8:30am, 14:30, noon), ranges
 * (8-9pm, 2pm to 3:30pm), durations ("for 1 hour", "90 min") and repeats (every day, weekdays,
 * every Mon, every Mon/Thu, weekly). What's left is the title.
 *
 * No time: an all-day event. A start with no end or duration: an hour. No title: null (not
 * understood). Pure; JVM-tested.
 */
object RuleParser {

    private val DAY_NAMES: List<Pair<String, DayOfWeek>> = listOf(
        "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
        "tues" to DayOfWeek.TUESDAY, "weds" to DayOfWeek.WEDNESDAY, "thurs" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY,
        "mon" to DayOfWeek.MONDAY, "tue" to DayOfWeek.TUESDAY, "wed" to DayOfWeek.WEDNESDAY, "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "sat" to DayOfWeek.SATURDAY, "sun" to DayOfWeek.SUNDAY,
    )
    private const val DAY = "(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday|tues|weds|thurs|thur|mon|tue|wed|thu|fri|sat|sun)s?"
    private const val MONTH = "(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\.?"
    private const val TIME = "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?"

    private val RX = RegexOption.IGNORE_CASE
    private val weekdaysRx = Regex("\\b(?:every\\s+weekday|(?:on\\s+)?weekdays)\\b", RX)
    private val dailyRx = Regex("\\b(?:every\\s+day|everyday|daily)\\b", RX)
    private val everyDaysRx = Regex("\\bevery\\s+($DAY(?:\\s*(?:/|,|&|and|\\+)\\s*$DAY)*)\\b", RX)
    private val weeklyRx = Regex("\\b(?:every\\s+week|weekly)\\b", RX)
    private val todayRx = Regex("\\b(today|tonight|this\\s+evening)\\b", RX)
    private val tomorrowRx = Regex("\\b(?:tomorrow|tmrw|tmr)\\b", RX)
    private val weekdayRx = Regex("\\b(?:(next|this)\\s+)?($DAY)\\b", RX)
    private val monthDayRx = Regex("\\b($MONTH)\\s+(\\d{1,2})(?:st|nd|rd|th)?\\b|\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+($MONTH)\\b", RX)
    private val rangeRx = Regex("\\b(?:from\\s+)?$TIME\\s*(?:-|–|—|to|until|till)\\s*$TIME(?![\\w:])", RX)
    private val timeRx = Regex("\\b(?:at\\s+)?(?:(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)|(\\d{1,2}):(\\d{2})|(noon|midday|midnight))(?![\\w:])", RX)
    private val durationRx = Regex("\\b(?:for\\s+)?(?:(\\d+(?:\\.\\d+)?)|(an?|half\\s+an?))\\s*(hours?|hrs?|h|minutes?|mins?|m)\\b", RX)
    private val edgeWords = Regex("^(?:(?:at|on|from|for|in|the|every|by|@|-|,)\\s+)+|(?:\\s+(?:at|on|from|for|in|the|every|by|@|-|,))+$", RX)

    fun parse(text: String, today: LocalDate, defaultDate: LocalDate = today): EventDraft? {
        var s = " " + text.trim() + " "
        fun cut(r: IntRange) {
            s = s.substring(0, r.first) + " ".repeat(r.last - r.first + 1) + s.substring(r.last + 1)
        }

        // ---- repeats
        var recurrence: Recurrence? = null
        weekdaysRx.find(s)?.let { recurrence = Recurrence(daily = false, days = Recurrence.WEEKDAYS); cut(it.range) }
        if (recurrence == null) dailyRx.find(s)?.let { recurrence = Recurrence.DAILY; cut(it.range) }
        if (recurrence == null) everyDaysRx.find(s)?.let { m ->
            val days = Regex(DAY, RX).findAll(m.groupValues[1]).mapNotNull { dayOf(it.value) }.toSet()
            if (days.isNotEmpty()) {
                recurrence = Recurrence(daily = false, days = days)
                cut(m.range)
            }
        }
        var weeklyOnDate = false
        if (recurrence == null) weeklyRx.find(s)?.let { weeklyOnDate = true; cut(it.range) }

        // ---- date
        var date: LocalDate? = null
        tomorrowRx.find(s)?.let { date = today.plusDays(1); cut(it.range) }
        if (date == null) todayRx.find(s)?.let { date = today; cut(it.range) }
        if (date == null) monthDayRx.find(s)?.let { m ->
            val monthWord = m.groupValues[1].ifEmpty { m.groupValues[4] }
            val dayNum = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull()
            val month = monthOf(monthWord)
            if (month != null && dayNum != null && dayNum in 1..month.maxLength()) {
                val md = MonthDay.of(month, dayNum)
                var d = md.atYear(today.year)
                if (d.isBefore(today)) d = md.atYear(today.year + 1)
                date = d
                cut(m.range)
            }
        }
        if (date == null && recurrence == null) weekdayRx.find(s)?.let { m ->
            val dow = dayOf(m.groupValues[2]) ?: return@let
            val ahead = ((dow.value - today.dayOfWeek.value + 7) % 7).toLong()
            date = today.plusDays(if (m.groupValues[1].equals("next", true) && ahead == 0L) 7 else ahead)
            cut(m.range)
        }

        // ---- time
        var start: LocalTime? = null
        var end: LocalTime? = null
        rangeRx.find(s)?.let { m ->
            val g = m.groupValues
            val endMer = mer(g[6])
            val startMer = mer(g[3])
            val e = time(g[4], g[5], endMer)
            // "8-9pm": the start takes the end's am/pm, unless that would put it after the end.
            var st = time(g[1], g[2], startMer ?: endMer)
            if (st != null && e != null && startMer == null && endMer != null && st.isAfter(e)) st = time(g[1], g[2], !endMer)
            if (st != null && e != null && (startMer != null || endMer != null || g[2].isNotEmpty() || g[5].isNotEmpty())) {
                start = st
                end = e
                cut(m.range)
            }
        }
        if (start == null) timeRx.find(s)?.let { m ->
            val g = m.groupValues
            start = when {
                g[6].isNotEmpty() -> if (g[6].lowercase() == "midnight") LocalTime.MIDNIGHT else LocalTime.NOON
                g[1].isNotEmpty() -> time(g[1], g[2], mer(g[3]))
                else -> time(g[4], g[5], null)
            }
            if (start != null) cut(m.range)
        }
        if (start != null && end == null) {
            durationRx.find(s)?.let { m ->
                val g = m.groupValues
                val n = g[1].toDoubleOrNull() ?: if (g[2].lowercase().startsWith("half")) 0.5 else 1.0
                val unit = g[3].lowercase()
                val minutes = if (unit.startsWith("h")) n * 60 else n
                if (minutes > 0 && minutes < 24 * 60) {
                    end = start!!.plusMinutes(minutes.toLong())
                    cut(m.range)
                }
            }
            if (end == null) end = start!!.plusMinutes(EventDraft.DEFAULT_MINUTES)
        }

        // ---- title: what's left
        val title = s.replace(Regex("\\s+"), " ").trim().replace(edgeWords, "").trim().trim(',', '-', '–').trim()
        if (title.isEmpty() || title.none { it.isLetter() }) return null

        var on = date ?: defaultDate
        if (weeklyOnDate) recurrence = Recurrence(daily = false, days = setOf(on.dayOfWeek))
        // A series with no date starts on its first day from the default one on.
        if (date == null && recurrence != null) on = defaultDate
        return EventDraft(
            title = title.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() },
            date = on,
            start = start,
            end = end,
            allDay = start == null,
            recurrence = recurrence,
        ).let { it.copy(date = it.firstDay()) }
    }

    private fun dayOf(word: String): DayOfWeek? {
        val w = word.lowercase().removeSuffix("s").ifEmpty { return null }
        return DAY_NAMES.firstOrNull { it.first == w || it.first == word.lowercase() }?.second
    }

    private fun monthOf(word: String): java.time.Month? {
        val w = word.lowercase().trimEnd('.').take(3)
        return java.time.Month.entries.firstOrNull { it.name.lowercase().take(3) == w }
    }

    /** true = pm, false = am, null = not said. */
    private fun mer(s: String): Boolean? = when {
        s.isEmpty() -> null
        s.lowercase().startsWith("p") -> true
        else -> false
    }

    private fun time(h: String, m: String, pm: Boolean?): LocalTime? {
        var hour = h.toIntOrNull() ?: return null
        val minute = if (m.isEmpty()) 0 else m.toIntOrNull() ?: return null
        if (minute !in 0..59) return null
        when (pm) {
            true -> { if (hour !in 1..12) return null; if (hour != 12) hour += 12 }
            false -> { if (hour !in 1..12) return null; if (hour == 12) hour = 0 }
            null -> if (hour !in 0..23) return null
        }
        return LocalTime.of(hour, minute)
    }

}
