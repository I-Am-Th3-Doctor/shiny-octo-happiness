package io.github.iamth3doctor.searchlight.widgets

import android.Manifest
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.calendar.CalendarWriter
import io.github.iamth3doctor.searchlight.calendar.EventDraft
import io.github.iamth3doctor.searchlight.calendar.EventParser
import io.github.iamth3doctor.searchlight.calendar.EventText
import io.github.iamth3doctor.searchlight.data.Prefs
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * The Calendar widget's "Add event", for the home screen: parses typed text ([EventParser]),
 * saves to the chosen calendar ([CalendarWriter], asking for calendar access first when it's
 * missing), confirms with a "Saved: …" snackbar whose Undo deletes the event again, and opens
 * the full edit sheet.
 */
class EventAdder(
    private val activity: Activity,
    private val prefs: Prefs,
    private val parser: EventParser,
    private val writer: CalendarWriter,
    private val words: () -> EventText.Words,
    private val arrangeWidgets: () -> Unit,
) : CalendarWidgetView.AddEventHost {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "event-adder").apply { isDaemon = true } }
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val locale get() = activity.resources.configuration.locales[0]

    /** An event waiting for calendar access to be granted, and what to tell when it's saved. */
    private var waiting: Pair<EventDraft, (Boolean) -> Unit>? = null

    /** The last event saved, and the last undone (tests). */
    @Volatile var lastSavedId: Long = -1
        private set
    @Volatile var lastUndoneId: Long = -1
        private set

    override fun parse(text: String, defaultDate: LocalDate, done: (EventDraft?) -> Unit) =
        parser.parse(text, LocalDate.now(zone), defaultDate, zone) { done(it.draft) }

    override val confirmBeforeSaving get() = prefs.calendarConfirm

    override fun calendarName(draft: EventDraft): String =
        if (!writer.canWrite()) "" else (writer.calendars().firstOrNull { it.id == draft.calendarId } ?: writer.target(prefs.calendarTarget))?.name.orEmpty()

    override fun save(draft: EventDraft, done: (Boolean) -> Unit) {
        if (!writer.canWrite()) {
            // Ask once; the event is saved as soon as access is granted (see onPermissionResult).
            waiting = draft to done
            activity.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), REQUEST_WRITE)
            return
        }
        worker.execute {
            val outcome = try {
                val cal = if (draft.calendarId >= 0) draft.calendarId else writer.target(prefs.calendarTarget)?.id
                if (cal == null) Outcome.NO_CALENDAR to -1L else Outcome.SAVED to writer.insert(draft, cal, zone)
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't save the event (${e.javaClass.simpleName})")
                Outcome.FAILED to -1L
            }
            main.post {
                when (outcome.first) {
                    Outcome.SAVED -> {
                        lastSavedId = outcome.second
                        saved(draft, outcome.second)
                        done(true)
                    }
                    Outcome.NO_CALENDAR -> {
                        Toast.makeText(activity, R.string.cal_add_no_calendar, Toast.LENGTH_LONG).show()
                        done(false)
                    }
                    Outcome.FAILED -> {
                        Toast.makeText(activity, R.string.cal_add_save_failed, Toast.LENGTH_LONG).show()
                        done(false)
                    }
                }
            }
        }
    }

    private enum class Outcome { SAVED, NO_CALENDAR, FAILED }

    /** "Saved: Gym, Fri Nov 6, 8:00–9:00 PM" with Undo. */
    private fun saved(draft: EventDraft, id: Long) {
        val fmt = DateFormat.getTimeFormat(activity)
        val summary = EventText.summary(draft, locale) { t: LocalTime ->
            fmt.format(java.util.Date.from(draft.date.atTime(t).atZone(zone).toInstant()))
        }
        Snackbar.show(activity, activity.getString(R.string.cal_add_saved, summary), activity.getString(R.string.cal_add_undo)) {
            worker.execute {
                val ok = try { writer.delete(id) } catch (e: Exception) { false }
                main.post {
                    if (ok) {
                        lastUndoneId = id
                        Toast.makeText(activity, R.string.cal_add_undone, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    override fun edit(draft: EventDraft, done: (Boolean) -> Unit) {
        worker.execute {
            val calendars = writer.calendars()
            val target = writer.target(prefs.calendarTarget)?.id ?: -1L
            main.post {
                EventEditSheet(activity, draft, calendars, target, words()) { edited -> save(edited, done) }.show()
            }
        }
    }

    override fun arrange() = arrangeWidgets()

    /** The answer to the calendar-access request [save] made. */
    fun onPermissionResult(granted: Boolean) {
        val (draft, done) = waiting ?: return
        waiting = null
        if (granted && writer.canWrite()) save(draft, done)
        else {
            Toast.makeText(activity, R.string.cal_add_needs_access, Toast.LENGTH_LONG).show()
            done(false)
        }
    }

    companion object {
        private const val TAG = "EventAdder"
        const val REQUEST_WRITE = 105
    }
}
