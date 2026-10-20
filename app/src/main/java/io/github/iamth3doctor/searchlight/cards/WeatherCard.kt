package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.content.Intent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.WidgetCenterActivity
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.weather.TempUnit
import io.github.iamth3doctor.searchlight.weather.Temperature
import io.github.iamth3doctor.searchlight.weather.WeatherRepository

/**
 * One line: icon, current temp, condition, ↑high ↓low. Tap: refresh (or set a location).
 * Long-press: switch °F/°C, refresh, weather settings.
 */
class WeatherCard(activity: Activity) : HomeCard(CardType.WEATHER, activity) {

    private val repo = activity.searchlight.weather
    private val prefs = activity.searchlight.prefs
    private lateinit var temp: TextView
    private lateinit var condition: TextView
    private lateinit var emoji: TextView
    private lateinit var hiLo: TextView

    private val listener: () -> Unit = { render() }

    override val layout = R.layout.card_weather

    override fun bind(root: View) {
        temp = root.findViewById(R.id.weatherTemp)
        condition = root.findViewById(R.id.weatherCondition)
        emoji = root.findViewById(R.id.weatherEmoji)
        hiLo = root.findViewById(R.id.weatherHiLo)
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
            temp.text = withUnit("--", unit)
            emoji.text = "🌡️"
            hiLo.text = ""
            condition.setText(
                when {
                    state.loading -> R.string.weather_loading
                    state.problem == WeatherRepository.Problem.NEEDS_LOCATION -> R.string.weather_needs_location
                    state.problem == WeatherRepository.Problem.LOCATION_UNAVAILABLE -> R.string.weather_no_fix
                    else -> R.string.weather_failed
                }
            )
            return
        }
        temp.text = withUnit(Temperature.format(data.temperatureC, unit), unit)
        condition.text = data.condition
        emoji.text = data.emoji
        hiLo.text = activity.getString(
            R.string.weather_hi_lo,
            Temperature.format(data.highC, unit),
            Temperature.format(data.lowC, unit),
        )
    }

    /** "72°F" with a smaller unit letter: the unit is always visible, never ambiguous. */
    private fun withUnit(value: String, unit: TempUnit): CharSequence {
        val letter = unit.symbol.removePrefix("°")
        val s = SpannableString(value + letter)
        s.setSpan(RelativeSizeSpan(0.6f), value.length, s.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
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
