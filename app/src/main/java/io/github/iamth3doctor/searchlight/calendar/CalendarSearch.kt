package io.github.iamth3doctor.searchlight.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract

/** An upcoming occurrence of a calendar event. */
class EventEntry(val eventId: Long, val title: String, val begin: Long, val end: Long, val allDay: Boolean, val location: String?)

/**
 * Upcoming calendar events whose title (or place) matches a query: the next [DAYS_AHEAD] days
 * of every calendar on the device, soonest first. Needs READ_CALENDAR (Settings › Permissions);
 * without it, no events. Runs on the background search thread.
 */
class CalendarSearch(private val context: Context) {

    fun hasPermission() = context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun search(query: String, limit: Int, now: Long = System.currentTimeMillis()): List<EventEntry> {
        val q = query.trim()
        if (q.length < MIN_QUERY || !hasPermission()) return emptyList()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, now)
            ContentUris.appendId(it, now + DAYS_AHEAD * 86_400_000L)
        }.build()
        val like = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val out = ArrayList<EventEntry>()
        try {
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
                ),
                "(${CalendarContract.Instances.TITLE} LIKE ? ESCAPE '\\' OR ${CalendarContract.Instances.EVENT_LOCATION} LIKE ? ESCAPE '\\')" +
                    " AND ${CalendarContract.Instances.END} >= ?",
                arrayOf(like, like, now.toString()),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    out += EventEntry(c.getLong(0), c.getString(1).orEmpty(), c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5))
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }

    private companion object {
        const val MIN_QUERY = 2
        const val DAYS_AHEAD = 60
    }
}
