package io.github.iamth3doctor.searchlight.calendar

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import android.util.Log
import java.util.concurrent.Executors

/**
 * The Calendar widget's events: every occurrence in a time range, from every visible calendar
 * on the device (the system Calendar Provider; nothing is fetched or stored anywhere else).
 * Queries run on a background thread; results come back on the main thread. Without
 * READ_CALENDAR there are no events, and nothing fails.
 */
class CalendarEvents(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "calendar-widget").apply { isDaemon = true } }

    /** Tests: stand in for the permission state (null: the real one). */
    @Volatile var permissionOverride: Boolean? = null

    /** Tests: stand in for the provider (null: the real one). Called on the background thread. */
    @Volatile var eventsOverride: ((from: Long, to: Long) -> List<DayEvent>)? = null

    /** How often the widget has asked Android for calendar access (tests). */
    var permissionRequests = 0

    fun hasPermission(): Boolean = permissionOverride
        ?: (context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED)

    /** Every occurrence overlapping [from, to), then [done] on the main thread. */
    fun load(from: Long, to: Long, done: (List<DayEvent>) -> Unit) {
        worker.execute {
            val list = try {
                eventsOverride?.invoke(from, to) ?: if (hasPermission()) query(from, to) else emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't read events", e)
                emptyList()
            }
            main.post { done(list) }
        }
    }

    private fun query(from: Long, to: Long): List<DayEvent> {
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from)
            ContentUris.appendId(it, to)
        }.build()
        val out = ArrayList<DayEvent>()
        context.contentResolver.query(
            uri,
            arrayOf(
                CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY,
                CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DISPLAY_COLOR,
            ),
            "${CalendarContract.Instances.VISIBLE} = 1",
            null,
            "${CalendarContract.Instances.BEGIN} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += DayEvent(
                    eventId = c.getLong(0),
                    title = c.getString(1).orEmpty(),
                    begin = c.getLong(2),
                    end = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    calendarName = c.getString(5).orEmpty(),
                    color = if (c.isNull(6)) 0 else c.getInt(6),
                )
            }
        }
        return out
    }

    private var observer: ContentObserver? = null

    /** [onChange] (main thread) whenever calendar data changes, until [stopWatching]. */
    fun watch(onChange: () -> Unit) {
        stopWatching()
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        try {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, o)
            observer = o
        } catch (e: Exception) {
            Log.w(TAG, "Can't watch the calendar", e)
        }
    }

    fun stopWatching() {
        observer?.let { runCatching { context.contentResolver.unregisterContentObserver(it) } }
        observer = null
    }

    private companion object {
        const val TAG = "CalendarEvents"
    }
}
