package io.github.iamth3doctor.searchlight.calendar

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * Reads [ClaudeEventParser]'s JSON back into an [EventDraft], checking every field: anything
 * missing or malformed means null, and the caller falls back to [RuleParser]. Pure; JVM-tested.
 */
object AiEventJson {

    fun toDraft(json: String): EventDraft? = try {
        val o = JSONObject(json)
        if (!o.optBoolean("understood", true)) null
        else {
            val title = o.getString("title").trim()
            val date = LocalDate.parse(o.getString("date").trim())
            val start = time(o.optString("start"))
            val end = time(o.optString("end"))
            val allDay = o.optBoolean("all_day", start == null) || start == null
            val days = o.optJSONArray("days")?.let { a -> (0 until a.length()).mapNotNull { Recurrence.dayOf(a.getString(it)) }.toSet() }.orEmpty()
            val recurrence = when (o.optString("repeat", "none")) {
                "daily" -> Recurrence.DAILY
                "weekly" -> Recurrence(daily = false, days = days.ifEmpty { setOf(date.dayOfWeek) })
                else -> null
            }
            if (title.isEmpty()) null
            else EventDraft(
                title = title,
                date = date,
                start = if (allDay) null else start,
                end = if (allDay) null else end ?: start!!.plusMinutes(EventDraft.DEFAULT_MINUTES),
                allDay = allDay,
                recurrence = recurrence,
            ).let { it.copy(date = it.firstDay()) }
        }
    } catch (e: Exception) {
        null
    }

    private fun time(s: String?): LocalTime? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        return try {
            LocalTime.parse(if (t.length == 4) "0$t" else t)
        } catch (e: Exception) {
            null
        }
    }
}
