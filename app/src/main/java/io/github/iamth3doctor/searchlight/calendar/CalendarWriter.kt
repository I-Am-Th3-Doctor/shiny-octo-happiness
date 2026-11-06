package io.github.iamth3doctor.searchlight.calendar

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Writes the Calendar widget's new events to the device Calendar Provider, on a calendar the
 * user can add to (the one chosen in Settings, else the primary Google calendar), and deletes
 * them again for Undo. Nothing is stored anywhere else. Needs WRITE_CALENDAR (and READ_CALENDAR
 * to list calendars); call off the main thread.
 */
class CalendarWriter(private val context: Context) {

    class Cal(val id: Long, val name: String, val account: String, val color: Int, val primary: Boolean, val google: Boolean)

    fun canWrite() = granted(Manifest.permission.WRITE_CALENDAR) && granted(Manifest.permission.READ_CALENDAR)

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Visible calendars that take new events, primary Google calendar first. */
    fun calendars(): List<Cal> {
        if (!granted(Manifest.permission.READ_CALENDAR)) return emptyList()
        val out = ArrayList<Cal>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME, CalendarContract.Calendars.ACCOUNT_TYPE,
                    CalendarContract.Calendars.CALENDAR_COLOR, CalendarContract.Calendars.IS_PRIMARY,
                    CalendarContract.Calendars.OWNER_ACCOUNT,
                ),
                "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}",
                null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val account = c.getString(2).orEmpty()
                    val primary = (!c.isNull(5) && c.getInt(5) == 1) || account == c.getString(6)
                    out += Cal(c.getLong(0), c.getString(1).orEmpty().ifEmpty { account }, account,
                        if (c.isNull(4)) 0 else c.getInt(4), primary, c.getString(3) == GOOGLE)
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out.sortedWith(compareByDescending<Cal> { it.google && it.primary }.thenByDescending { it.primary }.thenBy { it.name.lowercase() })
    }

    /** The calendar new events go on: [preferred] while it still exists, else the primary Google one, else the first. */
    fun target(preferred: Long): Cal? {
        val all = calendars()
        return all.firstOrNull { it.id == preferred } ?: all.firstOrNull()
    }

    /** Saves [draft] on calendar [calendarId]; the new event's id. */
    fun insert(draft: EventDraft, calendarId: Long, zone: ZoneId): Long {
        val v = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DTSTART, draft.beginMs(zone))
            put(CalendarContract.Events.ALL_DAY, if (draft.timed) 0 else 1)
            put(CalendarContract.Events.EVENT_TIMEZONE, if (draft.timed) zone.id else ZoneOffset.UTC.id)
            val r = draft.recurrence
            if (r != null) {
                // A series takes a duration and a rule instead of an end.
                put(CalendarContract.Events.RRULE, r.rrule)
                put(CalendarContract.Events.DURATION, draft.duration(zone))
            } else {
                put(CalendarContract.Events.DTEND, draft.endMs(zone))
            }
            if (draft.reminderMinutes != null) put(CalendarContract.Events.HAS_ALARM, 1)
        }
        val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, v) ?: error("insert refused")
        val id = ContentUris.parseId(uri)
        draft.reminderMinutes?.let { minutes ->
            context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Reminders.EVENT_ID, id)
                put(CalendarContract.Reminders.MINUTES, minutes)
                put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
            })
        }
        return id
    }

    /** Undo: removes the event (a whole series) just saved. */
    fun delete(eventId: Long): Boolean =
        context.contentResolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId), null, null) > 0

    private companion object {
        const val GOOGLE = "com.google"
    }
}
