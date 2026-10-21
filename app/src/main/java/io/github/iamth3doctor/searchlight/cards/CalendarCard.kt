package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.text.format.DateFormat
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.github.iamth3doctor.searchlight.R

/** Digital clock (a TextClock, so it ticks by itself) and the date, which scrolls if it doesn't fit. */
class CalendarCard(activity: Activity) : HomeCard(CardType.CALENDAR, activity) {

    private lateinit var date: TextView

    override val layout = R.layout.card_calendar

    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = render()
    }

    override fun bind(root: View) {
        date = root.findViewById(R.id.calendarDate)
        root.findViewById<View>(R.id.card).setOnClickListener { openCalendar() }
    }

    override fun onStart() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(activity, dateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        super.onStart()
    }

    override fun onStop() {
        try { activity.unregisterReceiver(dateReceiver) } catch (e: Exception) { /* not registered */ }
        super.onStop()
    }

    override fun draw() {
        date.text = DateFormat.format("EEEE, MMMM d", System.currentTimeMillis())
    }

    private fun openCalendar() {
        val uri = Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
        }
    }
}
