package io.github.iamth3doctor.searchlight

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.iamth3doctor.searchlight.cards.CardType
import io.github.iamth3doctor.searchlight.ui.MaxWidthLinearLayout
import io.github.iamth3doctor.searchlight.weather.TempUnit
import java.util.Date

/**
 * Widget Center: which built-in cards are on and in what order, which one is the idle card,
 * Music auto-front, and weather settings (°F/°C, location).
 */
class WidgetCenterActivity : Activity() {

    private val app get() = searchlight
    private val prefs get() = app.prefs
    private lateinit var content: LinearLayout
    private val weatherChanged: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.widget_center)
        content = MaxWidthLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
            maxWidthPx = resources.getDimensionPixelSize(R.dimen.content_max_width)
        }
        // Centered, capped column like the rest of the app, so it reads well unfolded.
        setContentView(ScrollView(this).apply {
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        })
        app.weather.addListener(weatherChanged)
    }

    override fun onDestroy() {
        app.weather.removeListener(weatherChanged)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        content.removeAllViews()

        section(R.string.wc_section_cards, first = true)
        help(R.string.wc_cards_help)
        val order = prefs.cardOrder
        val off = prefs.disabledCards
        order.forEachIndexed { i, type -> cardRow(type, i, order, type !in off) }

        section(R.string.wc_section_idle)
        val enabled = prefs.enabledCards
        row(getString(R.string.wc_idle_card), getString(prefs.idleCard.title)) { pickIdleCard() }
        switchRow(R.string.wc_music_front, prefs.musicAutoFront) { prefs.musicAutoFront = it }
        if (enabled.isEmpty()) help(R.string.wc_no_cards)

        section(R.string.wc_section_weather)
        unitPicker()
        val place = prefs.manualPlace
        row(
            getString(R.string.wc_location),
            place?.label ?: getString(if (app.weather.hasLocationPermission()) R.string.wc_location_device else R.string.wc_location_device_needs_permission),
        ) { pickLocation() }
        val data = app.weather.state.data
        row(
            getString(R.string.weather_refresh),
            if (data == null) getString(R.string.wc_never_updated)
            else getString(R.string.weather_updated, DateFormat.getTimeFormat(this).format(Date(data.fetchedAt))) + " · " + data.locationName,
        ) { app.weather.refresh() }
        help(R.string.wc_weather_privacy)
    }

    // ------------------------------------------------------------------ cards

    private fun cardRow(type: CardType, index: Int, order: List<CardType>, on: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(this).apply {
            setText(type.title)
            textSize = 16f
            isChecked = on
            setOnCheckedChangeListener { _, checked ->
                prefs.disabledCards = if (checked) prefs.disabledCards - type else prefs.disabledCards + type
                render()
            }
        }
        texts.addView(sw)
        texts.addView(TextView(this).apply {
            setText(type.description)
            textSize = 13f
            alpha = 0.7f
        })
        row.addView(texts)
        row.addView(arrow(R.drawable.ic_arrow_up, R.string.move_up, sw, index > 0) { move(order, index, -1) })
        row.addView(arrow(R.drawable.ic_arrow_down, R.string.move_down, sw, index < order.lastIndex) { move(order, index, +1) })
        content.addView(row)
    }

    private fun move(order: List<CardType>, index: Int, delta: Int) {
        val list = order.toMutableList()
        list.add(index + delta, list.removeAt(index))
        prefs.cardOrder = list
        render()
    }

    private fun pickIdleCard() {
        val choices = prefs.enabledCards.ifEmpty { return }
        AlertDialog.Builder(this)
            .setTitle(R.string.wc_idle_card)
            .setSingleChoiceItems(choices.map { getString(it.title) }.toTypedArray(), choices.indexOf(prefs.idleCard)) { d, which ->
                prefs.idleCard = choices[which]
                d.dismiss()
                render()
            }
            .show()
    }

    // ------------------------------------------------------------------ weather

    private fun unitPicker() {
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        TempUnit.entries.forEach { unit ->
            group.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = unit.symbol
                textSize = 16f
                isChecked = prefs.tempUnit == unit
                setPadding(dp(4), 0, dp(24), 0)
                setOnClickListener { prefs.tempUnit = unit }
            })
        }
        content.addView(TextView(this).apply {
            setText(R.string.wc_units)
            textSize = 16f
            setPadding(0, dp(8), 0, 0)
        })
        content.addView(group)
    }

    private fun pickLocation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wc_location)
            .setItems(arrayOf(getString(R.string.wc_use_device_location), getString(R.string.wc_search_place))) { _, which ->
                if (which == 0) useDeviceLocation() else searchPlace()
            }
            .show()
    }

    private fun useDeviceLocation() {
        prefs.manualPlace = null
        if (app.weather.hasLocationPermission()) {
            app.weather.refresh()
            render()
        } else {
            prefs.markAsked(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (!app.weather.hasLocationPermission()) Toast.makeText(this, R.string.wc_location_denied, Toast.LENGTH_LONG).show()
            app.weather.refresh()
            render()
        }
    }

    private fun searchPlace() {
        val input = EditText(this).apply {
            hint = getString(R.string.wc_search_place_hint)
            setSingleLine()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wc_search_place)
            .setView(box)
            .setPositiveButton(R.string.wc_search) { _, _ ->
                val q = input.text.toString().trim()
                if (q.length < 2) return@setPositiveButton
                app.weather.searchPlaces(q) { places ->
                    when {
                        isFinishing -> Unit
                        places == null -> Toast.makeText(this, R.string.wc_search_failed, Toast.LENGTH_LONG).show()
                        places.isEmpty() -> Toast.makeText(this, getString(R.string.wc_no_places, q), Toast.LENGTH_LONG).show()
                        else -> AlertDialog.Builder(this)
                            .setTitle(R.string.wc_pick_place)
                            .setItems(places.map { it.label }.toTypedArray()) { _, which ->
                                prefs.manualPlace = places[which]
                                app.weather.refresh()
                                render()
                            }
                            .show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ building blocks

    private fun section(text: Int, first: Boolean = false) {
        content.addView(TextView(this).apply {
            setText(text)
            setTextColor(getColor(R.color.icon_bg))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, if (first) 0 else dp(24), 0, dp(8))
        })
    }

    private fun help(text: Int) {
        content.addView(TextView(this).apply {
            setText(text)
            textSize = 13f
            alpha = 0.7f
            setPadding(0, 0, 0, dp(4))
        })
    }

    private fun row(title: String, summary: String, onClick: () -> Unit) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = dp(56)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            background = getDrawable(resolveAttr(android.R.attr.selectableItemBackground))
            setOnClickListener { onClick() }
            addView(TextView(context).apply { text = title; textSize = 16f })
            addView(TextView(context).apply { text = summary; textSize = 13f; alpha = 0.7f })
        })
    }

    private fun switchRow(text: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        content.addView(Switch(this).apply {
            setText(text)
            textSize = 16f
            minHeight = dp(56)
            isChecked = checked
            setOnCheckedChangeListener { _, c -> onChange(c) }
        })
    }

    private fun arrow(icon: Int, desc: Int, tintFrom: TextView, enabled: Boolean, onClick: () -> Unit) =
        ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = getString(desc)
            imageTintList = tintFrom.textColors
            background = null
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.25f
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
            setOnClickListener { onClick() }
        }

    private fun resolveAttr(attr: Int): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(attr, out, true)
        return out.resourceId
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val REQUEST_LOCATION = 1
    }
}
