package io.github.iamth3doctor.searchlight.calendar

import android.Manifest
import android.accounts.Account
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Creates, edits and deletes the Calendar widget's events in the device Calendar Provider, which
 * is the same store the Google Calendar app reads and Google's sync adapter uploads. Searchlight
 * keeps no events of its own.
 *
 * New events only ever go on a calendar that syncs with an account ([CalendarWriter.writable]):
 * never on a calendar that lives only on this phone (account type LOCAL), whose events Google
 * Calendar never sees. Edits and deletions change the event's own row, so they reach the same
 * Google Calendar entry on the next sync; after each change Searchlight asks Android to sync that
 * account now rather than whenever it next would.
 *
 * Events already stranded on phone-only calendars stay readable and deletable, and
 * [moveLocalEvents] moves them (copy, then delete the original: never both) to a synced calendar.
 *
 * Needs READ_CALENDAR and WRITE_CALENDAR; call off the main thread.
 */
class CalendarWriter(private val context: Context) {

    class Cal(
        val id: Long, val name: String, val account: String, val accountType: String,
        val color: Int, val primary: Boolean, val accessLevel: Int,
    ) {
        val google get() = accountType == GOOGLE
        /** Lives only on this phone: nothing syncs it anywhere. */
        val localOnly get() = accountType == CalendarContract.ACCOUNT_TYPE_LOCAL
        val canWrite get() = accessLevel >= Calendars.CAL_ACCESS_CONTRIBUTOR
    }

    fun canRead() = granted(Manifest.permission.READ_CALENDAR)
    fun canWrite() = granted(Manifest.permission.WRITE_CALENDAR) && canRead()

    private fun granted(p: String) = context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    /** Every calendar on the device (visible or not). */
    fun all(): List<Cal> {
        if (!canRead()) return emptyList()
        val out = ArrayList<Cal>()
        try {
            context.contentResolver.query(
                Calendars.CONTENT_URI,
                arrayOf(
                    Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
                    Calendars.CALENDAR_COLOR, Calendars.IS_PRIMARY, Calendars.OWNER_ACCOUNT, Calendars.CALENDAR_ACCESS_LEVEL,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val account = c.getString(2).orEmpty()
                    val primary = (!c.isNull(5) && c.getInt(5) == 1) || (account.isNotEmpty() && account == c.getString(6))
                    out += Cal(c.getLong(0), c.getString(1).orEmpty().ifEmpty { account }, account, c.getString(3).orEmpty(),
                        if (c.isNull(4)) 0 else c.getInt(4), primary, if (c.isNull(7)) 0 else c.getInt(7))
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return out
    }

    /**
     * Calendars new events may go on: synced with an account (never phone-only) and open to new
     * events. Primary Google calendars first, then other Google ones, then the rest. With several
     * Google accounts each has a primary calendar: the one first on the phone (its calendar was
     * made first, so has the lowest id) leads, as the phone's main account, never the
     * alphabetically first.
     */
    fun writable(): List<Cal> = all().filter { it.canWrite && !it.localOnly }
        .sortedWith(compareByDescending<Cal> { it.google && it.primary }.thenByDescending { it.google }.thenByDescending { it.primary }.thenBy { it.id })

    /** Where new events go: [preferred] while it's still writable, else the primary Google calendar (or the first synced one). */
    fun target(preferred: Long): Cal? {
        val list = writable()
        return list.firstOrNull { it.id == preferred } ?: list.firstOrNull()
    }

    fun calendar(id: Long): Cal? = all().firstOrNull { it.id == id }

    // ------------------------------------------------------------------ create / edit

    /** Saves a new event on [calendarId] (which must be [writable]); the new event's id. */
    fun insert(draft: EventDraft, calendarId: Long, zone: ZoneId): Long {
        val cal = calendar(calendarId)
        require(cal != null && cal.canWrite && !cal.localOnly) { "not a synced, writable calendar" }
        val v = values(draft, zone).apply { put(Events.CALENDAR_ID, calendarId) }
        val uri = context.contentResolver.insert(Events.CONTENT_URI, v) ?: error("insert refused")
        val id = ContentUris.parseId(uri)
        setReminder(id, draft.reminderMinutes)
        requestSync(cal)
        return id
    }

    /**
     * Saves changes to an existing event (its whole series, for a repeating one) in place: the
     * same row, so the same Google Calendar entry. A changed calendar moves it there.
     */
    fun update(draft: EventDraft, zone: ZoneId): Boolean {
        require(draft.eventId >= 0)
        val v = values(draft, zone)
        val current = calendarOf(draft.eventId)?.id
        if (draft.calendarId >= 0 && draft.calendarId != current) {
            // Moving it: only ever to a synced calendar.
            val cal = calendar(draft.calendarId)
            require(cal != null && cal.canWrite && !cal.localOnly) { "not a synced, writable calendar" }
            v.put(Events.CALENDAR_ID, draft.calendarId)
        }
        if (draft.recurrence == null && draft.otherRule == null) v.putNull(Events.RRULE) // no longer repeats: an end, not a duration
        val n = context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, draft.eventId), v, null, null)
        setReminder(draft.eventId, draft.reminderMinutes)
        calendarOf(draft.eventId)?.let(::requestSync)
        return n > 0
    }

    private fun values(draft: EventDraft, zone: ZoneId) = ContentValues().apply {
        put(Events.TITLE, draft.title)
        put(Events.EVENT_LOCATION, draft.location)
        put(Events.DTSTART, draft.beginMs(zone))
        put(Events.ALL_DAY, if (draft.timed) 0 else 1)
        put(Events.EVENT_TIMEZONE, if (draft.timed) zone.id else ZoneOffset.UTC.id)
        val rule = draft.recurrence?.rrule ?: draft.otherRule
        if (rule != null) {
            // A series takes a duration and a rule instead of an end.
            put(Events.RRULE, rule)
            put(Events.DURATION, draft.duration(zone))
            putNull(Events.DTEND)
        } else {
            put(Events.DTEND, draft.endMs(zone))
            putNull(Events.DURATION)
        }
        put(Events.HAS_ALARM, if (draft.reminderMinutes != null) 1 else 0)
    }

    private fun setReminder(eventId: Long, minutes: Int?) {
        val cr = context.contentResolver
        cr.delete(CalendarContract.Reminders.CONTENT_URI, "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()))
        if (minutes == null) return
        cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
    }

    /** An existing event as a draft for the edit sheet; null if it's gone. */
    fun draftOf(eventId: Long, zone: ZoneId): EventDraft? {
        val cr = context.contentResolver
        val draft = cr.query(
            ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
            arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.RRULE,
                Events.CALENDAR_ID, Events.EVENT_LOCATION, Events.EVENT_TIMEZONE),
            null, null, null,
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val allDay = c.getInt(4) == 1
            val begin = c.getLong(1)
            val end = if (!c.isNull(2)) c.getLong(2) else begin + durationMs(c.getString(3), allDay)
            val z = if (allDay) ZoneOffset.UTC else zone
            val startAt = Instant.ofEpochMilli(begin).atZone(z)
            val endAt = Instant.ofEpochMilli(end).atZone(z)
            EventDraft(
                title = c.getString(0).orEmpty(),
                date = startAt.toLocalDate(),
                start = if (allDay) null else startAt.toLocalTime().withSecond(0).withNano(0),
                end = if (allDay) null else endAt.toLocalTime().withSecond(0).withNano(0),
                allDay = allDay,
                recurrence = c.getString(5)?.let(::recurrenceOf),
                otherRule = c.getString(5)?.takeIf { recurrenceOf(it) == null },
                calendarId = c.getLong(6),
                location = c.getString(7).orEmpty(),
                eventId = eventId,
            )
        } ?: return null
        val reminder = cr.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(eventId.toString()), null)?.use { c -> if (c.moveToFirst()) c.getInt(0) else null }
        return draft.copy(reminderMinutes = reminder)
    }

    // ------------------------------------------------------------------ delete

    /**
     * Deletes the event (a whole series). As an app, not a sync adapter: on a Google calendar the
     * row is marked deleted and Google's sync adapter removes it from Google Calendar, then here.
     */
    fun delete(eventId: Long): Boolean {
        val cal = calendarOf(eventId)
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val n = if (cal != null && cal.localOnly) context.contentResolver.delete(asLocalSyncAdapter(uri, cal), null, null)
        else context.contentResolver.delete(uri, null, null)
        cal?.takeIf { !it.localOnly }?.let(::requestSync)
        return n > 0
    }

    /**
     * Deletes one occurrence of a repeating event (the one starting at [instanceBegin]) by adding
     * it to the series' excluded dates (EXDATE): a change to the series' own row, which Google
     * syncs like any edit, and which leaves every other occurrence as it was.
     */
    fun deleteOccurrence(eventId: Long, instanceBegin: Long): Boolean {
        val cr = context.contentResolver
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, eventId)
        val (existing, allDay) = cr.query(uri, arrayOf(Events.EXDATE, Events.ALL_DAY), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) to (c.getInt(1) == 1) else null } ?: return false
        val n = cr.update(uri, ContentValues().apply { put(Events.EXDATE, exdateWith(existing, instanceBegin, allDay)) }, null, null)
        calendarOf(eventId)?.takeIf { !it.localOnly }?.let(::requestSync)
        return n > 0
    }

    /** One occurrence that was already changed on its own (an exception row): cancel just it. */
    fun cancelException(exceptionId: Long): Boolean {
        val n = context.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, exceptionId),
            ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }, null, null)
        calendarOf(exceptionId)?.takeIf { !it.localOnly }?.let(::requestSync)
        return n > 0
    }

    // ------------------------------------------------------------------ phone-only events

    /** Events on calendars that live only on this phone (Google Calendar can't see them). */
    fun localEventCount(): Int {
        val ids = all().filter { it.localOnly }.map { it.id }
        if (ids.isEmpty()) return 0
        return context.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID),
            "${Events.CALENDAR_ID} IN (${ids.joinToString(",")}) AND ${Events.DELETED} = 0 AND ${Events.ORIGINAL_ID} IS NULL", null, null)
            ?.use { it.count } ?: 0
    }

    class MoveResult(val moved: Int, val failed: Int)

    /**
     * Moves every event on a phone-only calendar to [targetId] (a synced calendar): each is copied
     * there, and only once the copy exists is the original deleted, so nothing is lost and nothing
     * ends up twice. Run once, from Settings.
     */
    fun moveLocalEvents(targetId: Long): MoveResult {
        val target = calendar(targetId)
        require(target != null && target.canWrite && !target.localOnly) { "not a synced, writable calendar" }
        val locals = all().filter { it.localOnly }
        val cr = context.contentResolver
        var moved = 0
        var failed = 0
        for (cal in locals) {
            val rows = ArrayList<Pair<Long, ContentValues>>()
            cr.query(Events.CONTENT_URI, COPY_COLUMNS + Events._ID,
                "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0 AND ${Events.ORIGINAL_ID} IS NULL", arrayOf(cal.id.toString()), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val v = ContentValues()
                    COPY_COLUMNS.forEachIndexed { i, col ->
                        when {
                            c.isNull(i) -> Unit
                            c.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER -> v.put(col, c.getLong(i))
                            else -> v.put(col, c.getString(i))
                        }
                    }
                    rows += c.getLong(COPY_COLUMNS.size) to v
                }
            }
            for ((oldId, v) in rows) {
                try {
                    v.put(Events.CALENDAR_ID, targetId)
                    val newUri = cr.insert(Events.CONTENT_URI, v) ?: throw IllegalStateException("insert refused")
                    copyReminders(oldId, ContentUris.parseId(newUri))
                    cr.delete(asLocalSyncAdapter(ContentUris.withAppendedId(Events.CONTENT_URI, oldId), cal), null, null)
                    moved++
                } catch (e: Exception) {
                    failed++
                }
            }
        }
        if (moved > 0) requestSync(target)
        return MoveResult(moved, failed)
    }

    private fun copyReminders(from: Long, to: Long) {
        val cr = context.contentResolver
        val minutes = ArrayList<Int>()
        cr.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES),
            "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(from.toString()), null)?.use { c -> while (c.moveToNext()) minutes += c.getInt(0) }
        for (m in minutes) cr.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, to)
            put(CalendarContract.Reminders.MINUTES, m)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
        })
    }

    // ------------------------------------------------------------------ helpers

    private fun calendarOf(eventId: Long): Cal? {
        val calId = context.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), arrayOf(Events.CALENDAR_ID), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return null
        return calendar(calId)
    }

    /** A phone-only calendar has no sync adapter to finish a deletion: delete as its (local) sync adapter, for good. */
    private fun asLocalSyncAdapter(uri: Uri, cal: Cal): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, cal.account)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, cal.accountType)
        .build()

    /** Asks Android to sync [cal]'s account now (Google's sync adapter does the upload; Searchlight makes no network call). */
    private fun requestSync(cal: Cal) {
        if (cal.localOnly || cal.account.isEmpty()) return
        lastSyncRequested = cal.account
        try {
            ContentResolver.requestSync(Account(cal.account, cal.accountType), CalendarContract.AUTHORITY, Bundle().apply {
                putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
            })
        } catch (e: Exception) {
            // Best effort: the account syncs on its own schedule anyway.
        }
    }

    companion object {
        const val GOOGLE = "com.google"

        /** The last account a sync was requested for (tests). */
        @Volatile var lastSyncRequested: String? = null

        private val COPY_COLUMNS = arrayOf(
            Events.TITLE, Events.DESCRIPTION, Events.EVENT_LOCATION, Events.DTSTART, Events.DTEND, Events.DURATION,
            Events.ALL_DAY, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.EVENT_TIMEZONE,
            Events.EVENT_END_TIMEZONE, Events.HAS_ALARM, Events.AVAILABILITY, Events.ACCESS_LEVEL,
        )

        /** An RFC 5545 duration ("P3600S", "PT1H", "P1D", "P1W") in ms; a default when missing. */
        fun durationMs(rfc: String?, allDay: Boolean): Long {
            val fallback = if (allDay) 86_400_000L else 3_600_000L
            val d = rfc?.uppercase() ?: return fallback
            return Regex("(\\d+)([WDHMS])").findAll(d).sumOf { m ->
                val n = m.groupValues[1].toLong()
                when (m.groupValues[2]) {
                    "W" -> n * 604_800_000L
                    "D" -> n * 86_400_000L
                    "H" -> n * 3_600_000L
                    // M is minutes after the T, months before it (not used in event durations).
                    "M" -> n * 60_000L
                    else -> n * 1000L
                }
            }.takeIf { it > 0 } ?: fallback
        }

        /**
         * [existing] EXDATE plus the occurrence at [instanceBegin], in the provider's format: UTC
         * date-times ("20261106T140000Z"), comma-separated; all-day ones are UTC midnights.
         */
        fun exdateWith(existing: String?, instanceBegin: Long, allDay: Boolean): String {
            val t = Instant.ofEpochMilli(instanceBegin).atZone(ZoneOffset.UTC)
            val stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(if (allDay) t.toLocalDate().atStartOfDay(ZoneOffset.UTC) else t)
            val parts = existing.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return (if (stamp in parts) parts else parts + stamp).joinToString(",")
        }

        /** "FREQ=WEEKLY;BYDAY=MO,TH" back to a [Recurrence]; null for rules the sheet can't show. */
        fun recurrenceOf(rrule: String): Recurrence? {
            val parts = rrule.split(';').mapNotNull { p -> p.split('=').takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
            return when (parts["FREQ"]?.uppercase()) {
                "DAILY" -> Recurrence.DAILY
                "WEEKLY" -> parts["BYDAY"]?.split(',')?.mapNotNull { Recurrence.dayOf(it.takeLast(2)) }?.toSet()?.takeIf { it.isNotEmpty() }
                    ?.let { Recurrence(daily = false, days = it) }
                else -> null
            }
        }
    }
}
