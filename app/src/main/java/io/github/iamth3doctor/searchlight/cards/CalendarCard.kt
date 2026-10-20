package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.View
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R

/** Digital clock and date on one line (both TextClocks, so they tick by themselves). */
class CalendarCard(activity: Activity) : HomeCard(CardType.CALENDAR, activity) {

    override val layout = R.layout.card_calendar

    override fun bind(root: View) {
        root.findViewById<View>(R.id.card).setOnClickListener { openCalendar() }
    }

    override fun draw() = Unit

    private fun openCalendar() {
        val uri = Uri.parse("content://com.android.calendar/time/${System.currentTimeMillis()}")
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            Toast.makeText(activity, R.string.no_app_for_action, Toast.LENGTH_SHORT).show()
        }
    }
}
