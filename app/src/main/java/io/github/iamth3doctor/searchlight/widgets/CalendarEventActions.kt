package io.github.iamth3doctor.searchlight.widgets

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.calendar.CalendarWriter
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.calendar.EventDraft
import io.github.iamth3doctor.searchlight.calendar.EventText
import io.github.iamth3doctor.searchlight.data.Prefs
import io.github.iamth3doctor.searchlight.ui.Actions
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.Executors

/**
 * What the Calendar widget's events do, for the home screen: "+" and "Add event" open the add
 * sheet; tapping an event opens its detail view (Edit, Delete, Open in Calendar); long-pressing
 * one offers the same. Every change goes through [CalendarWriter] to the Calendar Provider, so it
 * reaches Google Calendar; nothing is kept anywhere else.
 *
 * Calendar access (WRITE_CALENDAR) is asked for the first time it's needed, after a short
 * explanation of why.
 */
class CalendarEventActions(
    private val activity: Activity,
    private val prefs: Prefs,
    private val writer: CalendarWriter,
    private val refresh: () -> Unit,
    private val arrangeWidgets: () -> Unit,
) : CalendarWidgetView.EventActions {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "calendar-actions").apply { isDaemon = true } }
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val locale get() = activity.resources.configuration.locales[0]

    /** What to do once calendar access is granted. */
    private var afterGrant: (() -> Unit)? = null

    private fun bg(work: () -> Unit) = worker.execute {
        try {
            work()
        } catch (e: Exception) {
            Log.w(TAG, "Calendar change failed (${e.javaClass.simpleName})")
            main.post { Toast.makeText(activity, R.string.cal_save_failed, Toast.LENGTH_LONG).show() }
        }
    }

    private fun ui(work: () -> Unit) = main.post { if (!activity.isFinishing && !activity.isDestroyed) work() }

    // ------------------------------------------------------------------ access

    /** Runs [then] with calendar access, asking for it first (after saying why) if needed. */
    private fun withWriteAccess(then: () -> Unit) {
        if (writer.canWrite()) return then()
        afterGrant = then
        lastDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.cal_write_why_title)
            .setMessage(R.string.cal_write_why)
            .setPositiveButton(R.string.cal_write_continue) { _, _ ->
                activity.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR), REQUEST_WRITE)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> afterGrant = null }
            .show()
    }

    /** The answer to [withWriteAccess]'s request. */
    fun onPermissionResult(granted: Boolean) {
        val next = afterGrant ?: return
        afterGrant = null
        if (granted && writer.canWrite()) next()
        else Toast.makeText(activity, R.string.cal_write_denied, Toast.LENGTH_LONG).show()
    }

    // ------------------------------------------------------------------ add

    override fun add(date: LocalDate) = withWriteAccess {
        bg {
            val calendars = writer.writable()
            val target = writer.target(prefs.calendarTarget)
            ui {
                if (target == null) {
                    // Never a phone-only calendar: with no synced one there's nowhere to put it.
                    lastDialog = AlertDialog.Builder(activity).setMessage(R.string.cal_no_google).setPositiveButton(android.R.string.ok, null).show()
                    return@ui
                }
                val now = LocalTime.now(zone)
                val start = if (date == LocalDate.now(zone) && now.hour < 23) now.withMinute(0).withSecond(0).withNano(0).plusHours(1) else LocalTime.of(9, 0)
                val draft = EventDraft(title = "", date = date, start = start, end = start.plusHours(1), allDay = false, calendarId = target.id)
                EventEditSheet(activity, draft, calendars, target.id, words()) { saveNew(it) }.show()
            }
        }
    }

    private fun saveNew(d: EventDraft) = bg {
        val cal = if (d.calendarId >= 0) d.calendarId else writer.target(prefs.calendarTarget)?.id ?: error("no calendar")
        // A calendar picked in the sheet is where the next event goes too (Settings shows it).
        if (cal != writer.target(prefs.calendarTarget)?.id) prefs.calendarTarget = cal
        val id = writer.insert(d, cal, zone)
        lastSavedId = id
        ui {
            refresh()
            Snackbar.show(activity, activity.getString(R.string.cal_saved, summary(d)), activity.getString(R.string.cal_undo)) {
                bg {
                    if (writer.delete(id)) ui {
                        lastDeletedId = id
                        refresh()
                        Toast.makeText(activity, R.string.cal_undone, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ details

    /** Title, when, calendar, location; Edit, Delete and Open in Calendar. Never a dead end. */
    override fun details(e: DayEvent) {
        bg {
            val cal = writer.calendar(e.calendarId)
            ui {
                val lines = mutableListOf(whenText(e))
                cal?.let { lines += if (it.account.isNotEmpty() && it.account != it.name) "${it.name} · ${it.account}" else it.name }
                e.location.takeIf { it.isNotBlank() }?.let { lines += it }
                if (cal?.localOnly == true) lines += activity.getString(R.string.cal_event_phone_only)
                val d = AlertDialog.Builder(activity)
                    .setTitle(e.title.ifBlank { activity.getString(R.string.cal_no_title) })
                    .setMessage(lines.joinToString("\n"))
                    .setPositiveButton(R.string.cal_event_edit, null)
                    .setNegativeButton(R.string.cal_event_delete, null)
                    .setNeutralButton(R.string.cal_event_open, null)
                    .create()
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { d.dismiss(); edit(e) }
                    d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { d.dismiss(); delete(e) }
                    d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { d.dismiss(); Actions.openEvent(activity, e.eventId, e.begin, e.end) }
                }
                d.show()
                lastDialog = d
                lastDetails = e
            }
        }
    }

    private fun whenText(e: DayEvent): String {
        val day = EventText.day(Instant.ofEpochMilli(e.begin).atZone(if (e.allDay) ZoneOffset.UTC else zone).toLocalDate(), locale)
        if (e.allDay) return "$day · ${activity.getString(R.string.cal_all_day)}"
        val fmt = DateFormat.getTimeFormat(activity)
        return "$day · ${EventText.range(fmt.format(e.begin), fmt.format(e.end))}"
    }

    /** Read-only calendars (holidays, shared views) can't take changes: say so instead of failing. */
    private fun readOnly(e: DayEvent, then: () -> Unit) {
        if (e.accessLevel >= android.provider.CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) return then()
        bg {
            val name = writer.calendar(e.calendarId)?.name.orEmpty()
            ui {
                lastDialog = AlertDialog.Builder(activity).setMessage(activity.getString(R.string.cal_event_read_only, name))
                    .setPositiveButton(android.R.string.ok, null).show()
            }
        }
    }

    // ------------------------------------------------------------------ edit

    /** The event's own row (a changed occurrence's own row), so the same Google Calendar entry changes. */
    override fun edit(e: DayEvent) = readOnly(e) {
        withWriteAccess {
            bg {
                val draft = writer.draftOf(e.eventId, zone)
                val writable = writer.writable()
                // A phone-only event keeps its calendar (it can be moved from Settings); others pick from synced ones.
                val calendars = if (draft != null && writable.none { it.id == draft.calendarId }) listOfNotNull(writer.calendar(draft.calendarId)) + writable else writable
                ui {
                    if (draft == null) {
                        Toast.makeText(activity, R.string.cal_event_gone, Toast.LENGTH_SHORT).show()
                        refresh()
                        return@ui
                    }
                    EventEditSheet(activity, draft, calendars, draft.calendarId, words()) { saveEdit(it) }.show()
                }
            }
        }
    }

    private fun saveEdit(d: EventDraft) = bg {
        val ok = writer.update(d, zone)
        ui {
            refresh()
            Toast.makeText(activity, if (ok) activity.getString(R.string.cal_updated, d.title) else activity.getString(R.string.cal_save_failed), Toast.LENGTH_SHORT).show()
            if (ok) lastUpdatedId = d.eventId
        }
    }

    // ------------------------------------------------------------------ delete

    /** Asks first; a repeating event asks which: this occurrence, or the whole series. */
    override fun delete(e: DayEvent) = readOnly(e) {
        withWriteAccess {
            bg {
                val cal = writer.calendar(e.calendarId)
                ui {
                    val name = e.title.ifBlank { activity.getString(R.string.cal_no_title) }
                    lastDialog = if (e.recurring) {
                        AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.cal_delete_repeating_title, name))
                            .setItems(arrayOf(activity.getString(R.string.cal_delete_this), activity.getString(R.string.cal_delete_all))) { _, which ->
                                if (which == 0) deleteOccurrence(e) else deleteSeries(e)
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    } else {
                        AlertDialog.Builder(activity)
                            .setTitle(activity.getString(R.string.cal_delete_title, name))
                            .setMessage(if (cal?.localOnly == true) activity.getString(R.string.cal_delete_message_local)
                                else activity.getString(R.string.cal_delete_message, cal?.name ?: ""))
                            .setPositiveButton(R.string.cal_event_delete) { _, _ -> deleteSeries(e) }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
        }
    }

    /** The whole event (for a repeating one, its series). */
    private fun deleteSeries(e: DayEvent) = bg {
        val id = if (e.originalId >= 0) e.originalId else e.eventId
        done(e, writer.delete(id), id)
    }

    /** Just this occurrence: a cancelled exception (or, if it was already changed on its own, that row cancelled). */
    private fun deleteOccurrence(e: DayEvent) = bg {
        val ok = if (e.originalId >= 0) writer.cancelException(e.eventId) else writer.deleteOccurrence(e.eventId, e.begin)
        done(e, ok, e.eventId)
    }

    private fun done(e: DayEvent, ok: Boolean, id: Long) = ui {
        refresh()
        if (ok) lastDeletedId = id
        Toast.makeText(activity, if (ok) activity.getString(R.string.cal_deleted, e.title) else activity.getString(R.string.cal_delete_failed), Toast.LENGTH_SHORT).show()
    }

    override fun arrange() = arrangeWidgets()

    // ------------------------------------------------------------------ text

    private fun words() = EventText.Words(
        activity.getString(R.string.cal_repeat_daily), activity.getString(R.string.cal_repeat_weekdays),
        { activity.getString(R.string.cal_repeat_every, it) }, activity.getString(R.string.cal_repeat_and),
    )

    private fun summary(d: EventDraft): String {
        val fmt = DateFormat.getTimeFormat(activity)
        return EventText.summary(d.copy(date = d.firstDay()), locale) { t: LocalTime ->
            fmt.format(java.util.Date.from(d.firstDay().atTime(t).atZone(zone).toInstant()))
        }
    }

    companion object {
        private const val TAG = "CalendarActions"
        const val REQUEST_WRITE = 105

        // What happened last (tests).
        @Volatile var lastSavedId = -1L
        @Volatile var lastUpdatedId = -1L
        @Volatile var lastDeletedId = -1L
        @Volatile var lastDetails: DayEvent? = null
        @Volatile var lastDialog: AlertDialog? = null
    }
}
