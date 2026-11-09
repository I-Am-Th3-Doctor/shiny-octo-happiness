package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.calendar.NextUp
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.ui.Actions

/**
 * The "Next up" tile: while a calendar event is in progress, or one starts within the hour, it's
 * the first tile in the strip ("• Team Sync starts in 14 min (Zoom)", "• In progress: Design
 * Review (Ends in 22 min)"), standing in for Calendar & Date while the Weather tile takes that
 * place (the home screen arranges the strip: see [onActiveChanged]). Past the event it leaves the
 * strip by itself. Events come from the Calendar Provider, through the same CalendarEvents as the
 * Calendar widget.
 *
 * Tap: the event's video-call link if it has one; otherwise the Month + Day calendar on the
 * event's day ([onOpenAgenda]). Never a dead end.
 */
class NextUpCard(activity: Activity) : HomeCard(CardType.CALENDAR, activity) {

    override val layout = R.layout.card_next_up
    override val rowFlexible = true

    private val events = activity.searchlight.calendarEvents
    private val main = Handler(Looper.getMainLooper())
    private var calendarWatch: Any? = null
    private var generation = 0
    private lateinit var line: TextView

    /** Now, in ms (tests set a fixed one). */
    var clock: () -> Long = System::currentTimeMillis

    /** What the tile shows, or null: no event in progress or within the hour (the tile is out of the strip). */
    var status: NextUp.Status? = null
        private set

    val active get() = status != null

    /** An event came into the window, or the last one left it: the home screen re-arranges the strip. */
    var onActiveChanged: ((Boolean) -> Unit)? = null

    /** A tapped event without a call link: the Month + Day calendar on its day. */
    var onOpenAgenda: ((DayEvent) -> Unit)? = null

    /** Once a minute, on the minute, while home is showing: the countdown moves, the window slides. */
    private val tick = object : Runnable {
        override fun run() {
            reload()
            val now = System.currentTimeMillis()
            main.postDelayed(this, 60_000L - now % 60_000L + 50L)
        }
    }

    override fun bind(root: View) {
        line = root.findViewById(R.id.nextUpLine)
        root.findViewById<View>(R.id.card).setOnClickListener { tap() }
    }

    /** The link, or the calendar on the event's day. */
    fun tap() {
        val s = status ?: return
        val call = s.call
        if (call != null) Actions.openCallLink(activity, call.url) else onOpenAgenda?.invoke(s.event)
    }

    override fun onStart() {
        super.onStart()
        calendarWatch = events.watch { reload() }
        main.removeCallbacks(tick)
        tick.run()
    }

    override fun onStop() {
        main.removeCallbacks(tick)
        events.stopWatching(calendarWatch)
        calendarWatch = null
        generation++
        super.onStop()
    }

    /** Re-reads what's in progress and the hour ahead. */
    fun reload() {
        if (!started) return
        val now = clock()
        val gen = ++generation
        events.load(now, now + NextUp.WINDOW_MS + 60_000L) { list ->
            if (gen != generation || !started) return@load
            set(NextUp.pick(list, clock()))
        }
    }

    private fun set(s: NextUp.Status?) {
        val was = status != null
        status = s
        render()
        if (was != (s != null)) onActiveChanged?.invoke(s != null)
    }

    /** "• Team Sync starts in 14 min (Zoom)": a gold bullet, the event in bold, the time after. */
    override fun draw() {
        val up = status ?: return
        val untitled = activity.getString(R.string.cal_no_title)
        val text = NextUp.text(up, untitled)
        val s = SpannableString("• $text")
        s.setSpan(ForegroundColorSpan(activity.getColor(R.color.gold)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val title = up.event.title.ifBlank { untitled }
        val at = s.indexOf(title)
        if (at >= 0) s.setSpan(StyleSpan(Typeface.BOLD), at, at + title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.text = s
        line.contentDescription = text + ". " + activity.getString(if (up.call != null) R.string.next_up_join else R.string.next_up_open)
    }

    /** What the tile's line says (tests). */
    internal val lineText: CharSequence? get() = if (::line.isInitialized) line.text else null
}
