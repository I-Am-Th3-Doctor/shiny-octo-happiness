package io.github.iamth3doctor.searchlight.widgets

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.calendar.CalendarWriter
import io.github.iamth3doctor.searchlight.calendar.EventDraft
import io.github.iamth3doctor.searchlight.calendar.EventText
import io.github.iamth3doctor.searchlight.calendar.Recurrence
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.ui.wrappingSingleChoice
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

/**
 * The add / edit sheet: title, location, date, all-day, start and end, repeat, calendar and
 * reminder. Save hands the event to [onSave]. [calendars] are the ones that sync with an account
 * (never a phone-only calendar). Editing a repeating event changes the whole series.
 */
class EventEditSheet(
    private val activity: Activity,
    start: EventDraft,
    private val calendars: List<CalendarWriter.Cal>,
    defaultCalendar: Long,
    private val words: EventText.Words,
    private val onSave: (EventDraft) -> Unit,
) {
    /** The event as edited so far. */
    var draft: EventDraft = start.copy(calendarId = if (start.calendarId >= 0) start.calendarId else defaultCalendar)
        private set

    private val locale get() = activity.resources.configuration.locales[0]

    /**
     * The sheet's own (dialog) theme: light, with dark text. Every view in it is built with this,
     * never with the activity, whose dark home theme would give white text on the white sheet.
     */
    private val builder = AlertDialog.Builder(activity)
    val ui: android.content.Context = builder.context
    private val textColor = themeColor(android.R.attr.textColorPrimary)
    private val hintColor = themeColor(android.R.attr.textColorSecondary)

    private fun themeColor(attr: Int): android.content.res.ColorStateList {
        val a = ui.obtainStyledAttributes(intArrayOf(attr))
        try {
            return a.getColorStateList(0) ?: android.content.res.ColorStateList.valueOf(android.graphics.Color.BLACK)
        } finally {
            a.recycle()
        }
    }

    /** Dark text and a dimmed (still readable) hint, whatever the state. */
    private fun TextView.legible() {
        setTextColor(textColor)
        setHintTextColor(hintColor)
    }

    val titleField = EditText(ui)
    val locationField = EditText(ui)
    private val editing get() = draft.eventId >= 0
    val allDaySwitch = Switch(ui)
    val dateRow = row(R.string.cal_edit_date) { pickDate() }
    val startRow = row(R.string.cal_edit_start) { pickTime(start = true) }
    val endRow = row(R.string.cal_edit_end) { pickTime(start = false) }
    val repeatRow = row(R.string.cal_edit_repeat) { pickRepeat() }
    val calendarRow = row(R.string.cal_edit_calendar) { pickCalendar() }
    val reminderRow = row(R.string.cal_edit_reminder) { pickReminder() }
    lateinit var dialog: AlertDialog
        private set

    fun show(): EventEditSheet {
        val pad = activity.px(R.dimen.space_l)
        val form = LinearLayout(ui).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, activity.px(R.dimen.space_s), pad, 0)
        }
        titleField.apply {
            type(Type.BODY)
            legible()
            setHint(R.string.cal_edit_name_hint)
            setText(draft.title)
            isSingleLine = true
            minHeight = activity.px(R.dimen.size_touch)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        form.addView(titleField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        locationField.apply {
            type(Type.BODY)
            legible()
            setHint(R.string.cal_edit_location_hint)
            setText(draft.location)
            isSingleLine = true
            minHeight = activity.px(R.dimen.size_touch)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        form.addView(locationField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (editing && draft.repeats) form.addView(TextView(ui).apply {
            type(Type.CAPTION)
            setTextColor(hintColor)
            setText(R.string.cal_edit_series_note)
        })
        allDaySwitch.apply {
            setText(R.string.cal_edit_all_day)
            type(Type.BODY)
            legible()
            minHeight = activity.px(R.dimen.size_touch)
            isChecked = draft.allDay
            setOnCheckedChangeListener { _, on ->
                draft = if (on) draft.copy(allDay = true)
                else draft.copy(allDay = false, start = draft.start ?: LocalTime.of(9, 0), end = draft.end ?: (draft.start ?: LocalTime.of(9, 0)).plusHours(1))
                render()
            }
        }
        form.addView(allDaySwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        for (r in listOf(dateRow, startRow, endRow, repeatRow, calendarRow, reminderRow)) form.addView(r)
        render()
        dialog = builder
            .setTitle(if (editing) R.string.cal_edit_title_edit else R.string.cal_edit_title_new)
            .setView(ScrollView(ui).apply { addView(form) })
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.cal_save_button, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { save() }
        }
        dialog.show()
        current = this
        dialog.setOnDismissListener { if (current === this) current = null }
        return this
    }

    /** Save: a title is needed; then the event goes to [onSave]. */
    fun save() {
        val title = titleField.text?.toString()?.trim().orEmpty()
        if (title.isEmpty()) {
            titleField.error = activity.getString(R.string.cal_edit_name_hint)
            return
        }
        draft = draft.copy(title = title, location = locationField.text?.toString()?.trim().orEmpty())
        dialog.dismiss()
        onSave(draft)
    }

    private fun row(label: Int, click: () -> Unit) = TextView(ui).apply {
        type(Type.BODY)
        legible()
        minHeight = activity.px(R.dimen.size_touch)
        gravity = android.view.Gravity.CENTER_VERTICAL
        tag = activity.getString(label)
        val tv = TypedValue()
        ui.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        if (tv.resourceId != 0) background = ui.getDrawable(tv.resourceId)
        setOnClickListener { click() }
    }

    private fun time(t: LocalTime): String =
        DateFormat.getTimeFormat(activity).format(java.util.Date.from(draft.date.atTime(t).atZone(ZoneId.systemDefault()).toInstant()))

    private fun set(row: TextView, value: String) {
        row.text = "${row.tag}: $value"
    }

    fun render() {
        val d = draft
        set(dateRow, EventText.day(d.date, locale))
        startRow.visibility = if (d.allDay) View.GONE else View.VISIBLE
        endRow.visibility = startRow.visibility
        if (!d.allDay) {
            set(startRow, time(d.start ?: LocalTime.of(9, 0)))
            set(endRow, time(d.end ?: (d.start ?: LocalTime.of(9, 0)).plusHours(1)))
        }
        set(repeatRow, d.recurrence?.let { EventText.repeat(it, locale, words) }
            ?: activity.getString(if (d.otherRule != null) R.string.cal_repeat_other else R.string.cal_repeat_none))
        set(calendarRow, calendars.firstOrNull { it.id == d.calendarId }?.name ?: calendars.firstOrNull()?.name ?: "—")
        set(reminderRow, reminderLabel(d.reminderMinutes))
    }

    private fun pickDate() {
        DatePickerDialog(activity, { _, y, m, day ->
            draft = draft.copy(date = java.time.LocalDate.of(y, m + 1, day))
            render()
        }, draft.date.year, draft.date.monthValue - 1, draft.date.dayOfMonth).show()
    }

    private fun pickTime(start: Boolean) {
        val now = (if (start) draft.start else draft.end) ?: LocalTime.of(9, 0)
        TimePickerDialog(activity, { _, h, m ->
            val t = LocalTime.of(h, m)
            draft = if (start) {
                // Moving the start keeps the length.
                val len = java.time.Duration.between(draft.start ?: t, draft.end ?: (draft.start ?: t).plusHours(1))
                    .let { if (it.isNegative || it.isZero) java.time.Duration.ofHours(1) else it }
                draft.copy(start = t, end = t.plus(len))
            } else draft.copy(end = t)
            render()
        }, now.hour, now.minute, DateFormat.is24HourFormat(activity)).show()
    }

    private fun pickRepeat() {
        val dow = draft.date.dayOfWeek
        val options: List<Pair<String, Recurrence?>> = listOf(
            activity.getString(R.string.cal_repeat_none) to null,
            words.daily to Recurrence.DAILY,
            words.weekdays to Recurrence(daily = false, days = Recurrence.WEEKDAYS),
            activity.getString(R.string.cal_repeat_weekly_on, dow.getDisplayName(TextStyle.FULL, locale)) to Recurrence.weekly(dow),
        )
        val labels = options.map { it.first } + activity.getString(R.string.cal_repeat_custom)
        val checked = options.indexOfFirst { it.second == draft.recurrence }.let { if (it < 0) labels.lastIndex else it }
        AlertDialog.Builder(activity).setTitle(R.string.cal_edit_repeat).wrappingSingleChoice(labels, checked) { d, which ->
            d.dismiss()
            if (which < options.size) {
                draft = draft.copy(recurrence = options[which].second, otherRule = null)
                render()
            } else pickDays()
        }.show()
    }

    private fun pickDays() {
        val all = java.time.DayOfWeek.entries
        val chosen = all.map { draft.recurrence?.days?.contains(it) == true || (draft.recurrence == null && it == draft.date.dayOfWeek) }.toBooleanArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.cal_edit_repeat)
            .setMultiChoiceItems(all.map { it.getDisplayName(TextStyle.FULL, locale) }.toTypedArray(), chosen) { _, i, on -> chosen[i] = on }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val days = all.filterIndexed { i, _ -> chosen[i] }.toSet()
                draft = draft.copy(recurrence = if (days.isEmpty()) null else Recurrence(daily = false, days = days), otherRule = null)
                render()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickCalendar() {
        if (calendars.isEmpty()) return
        val checked = calendars.indexOfFirst { it.id == draft.calendarId }.coerceAtLeast(0)
        lastPicker = AlertDialog.Builder(activity).setTitle(R.string.cal_edit_calendar)
            .wrappingSingleChoice(calendars.map { if (it.account.isNotEmpty() && it.account != it.name) "${it.name} · ${it.account}" else it.name }, checked) { d, which ->
                d.dismiss()
                draft = draft.copy(calendarId = calendars[which].id)
                render()
            }.show()
    }

    private fun reminderLabel(m: Int?): String = when (m) {
        null -> activity.getString(R.string.cal_reminder_none)
        0 -> activity.getString(R.string.cal_reminder_at_start)
        60 -> activity.getString(R.string.cal_reminder_hour)
        1440 -> activity.getString(R.string.cal_reminder_day)
        else -> activity.getString(R.string.cal_reminder_minutes, m)
    }

    private fun pickReminder() {
        val values = listOf(null, 0, 5, 10, 15, 30, 60, 1440)
        AlertDialog.Builder(activity).setTitle(R.string.cal_edit_reminder)
            .wrappingSingleChoice(values.map(::reminderLabel), values.indexOf(draft.reminderMinutes).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                draft = draft.copy(reminderMinutes = values[which])
                render()
            }.show()
    }

    companion object {
        /** The sheet showing (tests). */
        var current: EventEditSheet? = null
            private set

        /** The calendar picker last opened (tests). */
        var lastPicker: AlertDialog? = null
            private set
    }
}
