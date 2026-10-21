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
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.weather.Temperature
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import java.util.Date

/**
 * Icon, then one line: place · temp · condition · high/low · updated (it scrolls when it
 * doesn't fit). Tap: refresh (or set a location).
 * Long-press: switch °F/°C, refresh, weather settings.
 */
class WeatherCard(activity: Activity) : HomeCard(CardType.WEATHER, activity) {

    private val repo = activity.searchlight.weather
    private val prefs = activity.searchlight.prefs
    private lateinit var emoji: TextView
    private lateinit var line: TextView

    private val listener: () -> Unit = { render() }

    override val layout = R.layout.card_weather

    override fun bind(root: View) {
        emoji = root.findViewById(R.id.weatherEmoji)
        line = root.findViewById(R.id.weatherLine)
        val card = root.findViewById<View>(R.id.card)
        card.setOnClickListener {
            if (repo.state.problem == WeatherRepository.Problem.NEEDS_LOCATION) openSettings() else repo.refresh()
        }
        card.setOnLongClickListener { showMenu(it); true }
    }

    override fun onStart() {
        repo.addListener(listener)
        super.onStart()
    }

    override fun onStop() {
        repo.removeListener(listener)
        super.onStop()
    }

    override fun draw() {
        val state = repo.state
        val data = state.data
        val unit = prefs.tempUnit
        if (data == null) {
            emoji.text = "🌡️"
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
            menu.add(0, 3, 2, R.string.weather_settings)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> { prefs.tempUnit = other; render() }
                    2 -> repo.refresh()
                    3 -> openSettings()
                }
                true
            }
            show()
        }
    }

    private fun openSettings() = activity.startActivity(Intent(activity, WidgetCenterActivity::class.java))
}
