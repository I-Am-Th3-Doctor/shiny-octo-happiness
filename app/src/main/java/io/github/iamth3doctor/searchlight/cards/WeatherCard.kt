package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.WidgetCenterActivity
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.ui.pickWeatherApp
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.weather.Temperature
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import java.util.Date

/**
 * Icon, then one line: place · temp · condition · high/low · updated (it scrolls when it
 * doesn't fit). Tap: open your weather app (chosen on first tap), or set a location / retry
 * when there's no weather to show.
 * Long-press: switch °F/°C, refresh, weather settings.
 *
 * "Next up": while a calendar event is in progress, or one starts within the hour, the tile
 * shows it instead ("• Team Sync starts in 14 min (Zoom)", "• In progress: Design Review (Ends
 * in 22 min)"), from the same Calendar Provider events as the Calendar widget, and goes back to
 * weather by itself once that's past. Tapping it then joins the video call, if the event has a
 * link, or else shows the agenda ([onShowAgenda]). [onShowingEventChanged] lets the home screen
 * mark the tile's page dot.
 */
class WeatherCard(activity: Activity) : HomeCard(CardType.WEATHER, activity) {

    private val repo = activity.searchlight.weather
    private val prefs = activity.searchlight.prefs
    private lateinit var emoji: TextView
    private lateinit var line: TextView

    private val listener: () -> Unit = { render() }

    // ---- Next up
    private val events = activity.searchlight.calendarEvents
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var calendarWatch: Any? = null
    private var generation = 0

    /** Now, in ms (tests set a fixed one). */
    var clock: () -> Long = System::currentTimeMillis

    /** The event the tile shows instead of weather, or null (weather). */
    var nextUp: io.github.iamth3doctor.searchlight.calendar.NextUp.Status? = null
        private set

    /** The tile started or stopped showing an event (the home screen's gold page dot). */
    var onShowingEventChanged: ((Boolean) -> Unit)? = null

    /** A tapped event without a video link: the agenda. */
    var onShowAgenda: (() -> Unit)? = null

    /** Once a minute, on the minute, while home is showing: the countdown moves, the window slides. */
    private val tick = object : Runnable {
        override fun run() {
            reloadNextUp()
            val now = System.currentTimeMillis()
            main.postDelayed(this, 60_000L - now % 60_000L + 50L)
        }
    }

    /** Re-reads the hour ahead (and what's in progress) and redraws. */
    fun reloadNextUp() {
        if (!started) return
        val now = clock()
        val gen = ++generation
        events.load(now, now + io.github.iamth3doctor.searchlight.calendar.NextUp.WINDOW_MS + 60_000L) { list ->
            if (gen != generation || !started) return@load
            setNextUp(io.github.iamth3doctor.searchlight.calendar.NextUp.pick(list, clock()))
        }
    }

    private fun setNextUp(s: io.github.iamth3doctor.searchlight.calendar.NextUp.Status?) {
        val was = nextUp != null
        nextUp = s
        render()
        if (was != (s != null)) onShowingEventChanged?.invoke(s != null)
    }

    /** Unfolded, side by side: just the temperature, at a glance (the full line folded). */
    private var glance = false

    override fun onRowChanged(inRow: Boolean) {
        glance = inRow
        render()
    }

    override val layout = R.layout.card_weather

    override fun bind(root: View) {
        emoji = root.findViewById(R.id.weatherEmoji)
        line = root.findViewById(R.id.weatherLine)
        val card = root.findViewById<View>(R.id.card)
        card.setOnClickListener { v ->
            val up = nextUp
            when {
                up != null && up.call != null -> io.github.iamth3doctor.searchlight.ui.Actions.openCallLink(activity, up.call.url)
                up != null -> onShowAgenda?.invoke()
                repo.state.problem == WeatherRepository.Problem.NEEDS_LOCATION -> openSettings()
                repo.state.data == null -> repo.refresh() // the tile says "tap to retry"
                else -> weatherApp()?.let { activity.searchlight.apps.launch(it, v, activity) }
                    ?: activity.pickWeatherApp { picked -> picked?.let { activity.searchlight.apps.launch(it, v, activity) } }
            }
        }
        card.setOnLongClickListener { showMenu(it); true }
    }

    override fun onStart() {
        repo.addListener(listener)
        super.onStart()
        calendarWatch = events.watch { reloadNextUp() }
        main.removeCallbacks(tick)
        tick.run()
    }

    override fun onStop() {
        repo.removeListener(listener)
        main.removeCallbacks(tick)
        events.stopWatching(calendarWatch)
        calendarWatch = null
        generation++
        super.onStop()
    }

    override fun draw() {
        val up = nextUp
        if (up != null) {
            drawNextUp(up)
            return
        }
        emoji.visibility = View.VISIBLE
        line.contentDescription = null
        val state = repo.state
        val data = state.data
        val unit = prefs.tempUnit
        if (data == null) {
            emoji.text = "🌡️"
            if (glance) {
                line.text = withUnit("--°", unit) // "--°F" until a reading arrives, like "72°F" after
                return
            }
            line.text = joinedLine(
                withUnit("--", unit),
                activity.getString(
                    when {
                        state.loading -> R.string.weather_loading
                        state.problem == WeatherRepository.Problem.NEEDS_LOCATION -> R.string.weather_needs_location
                        state.problem == WeatherRepository.Problem.LOCATION_UNAVAILABLE -> R.string.weather_no_fix
                        else -> R.string.weather_failed
                    }
                ),
            )
            return
        }
        emoji.text = data.emoji
        if (glance) {
            line.text = withUnit(Temperature.format(data.temperatureC, unit), unit)
            return
        }
        val time = DateFormat.getTimeFormat(activity).format(Date(data.fetchedAt))
        line.text = joinedLine(
            data.locationName,
            withUnit(Temperature.format(data.temperatureC, unit), unit),
            data.condition,
            activity.getString(R.string.weather_hi_lo, Temperature.format(data.highC, unit), Temperature.format(data.lowC, unit)),
            activity.getString(
                when {
                    state.loading -> R.string.weather_updating
                    state.problem != null -> R.string.weather_stale
                    else -> R.string.weather_updated
                },
                time,
            ),
        )
    }

    /** What the tile's line says, and whether its weather icon shows (tests). */
    internal val lineText: CharSequence? get() = if (::line.isInitialized) line.text else null
    internal val iconShown: Boolean get() = ::emoji.isInitialized && emoji.visibility == View.VISIBLE

    /** "• Team Sync starts in 14 min (Zoom)": a gold bullet, the event in bold, the time after. */
    private fun drawNextUp(up: io.github.iamth3doctor.searchlight.calendar.NextUp.Status) {
        emoji.visibility = View.GONE
        val untitled = activity.getString(R.string.cal_no_title)
        val text = io.github.iamth3doctor.searchlight.calendar.NextUp.text(up, untitled)
        val s = SpannableString("• $text")
        s.setSpan(android.text.style.ForegroundColorSpan(activity.getColor(R.color.gold)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val title = up.event.title.ifBlank { untitled }
        val at = s.indexOf(title)
        if (at >= 0) s.setSpan(StyleSpan(Typeface.BOLD), at, at + title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        line.text = s
        line.contentDescription = text + if (up.call != null) ". " + activity.getString(R.string.next_up_join) else ""
    }

    /**
     * "72°F" in bold at tile-title size (the rest of the line is body size), with a smaller
     * unit letter: the unit is always visible, never ambiguous.
     */
    private fun withUnit(value: String, unit: TempUnit): CharSequence {
        val letter = unit.symbol.removePrefix("°")
        val scale = activity.pxF(R.dimen.text_tile_title) / activity.pxF(R.dimen.text_body)
        val s = SpannableString(value + letter)
        s.setSpan(StyleSpan(Typeface.BOLD), 0, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(RelativeSizeSpan(scale), 0, value.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        s.setSpan(RelativeSizeSpan(scale * 0.6f), value.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return s
    }

    private fun showMenu(anchor: View) {
        val other = if (prefs.tempUnit == TempUnit.FAHRENHEIT) TempUnit.CELSIUS else TempUnit.FAHRENHEIT
        PopupMenu(activity, anchor).apply {
            menu.add(0, 1, 0, activity.getString(R.string.weather_switch_unit, other.symbol))
            menu.add(0, 2, 1, R.string.weather_refresh)
            menu.add(0, 4, 2, R.string.weather_app_choose)
            menu.add(0, 3, 3, R.string.weather_settings)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { prefs.tempUnit = other; render() }
                    2 -> repo.refresh()
                    3 -> openSettings()
                    4 -> activity.pickWeatherApp()
                }
                true
            }
            show()
        }
    }

    /** The chosen weather app, if it's still installed. */
    private fun weatherApp() = prefs.weatherAppKey?.let { activity.searchlight.apps.find(it) }

    private fun openSettings() = activity.startActivity(Intent(activity, WidgetCenterActivity::class.java))
}
