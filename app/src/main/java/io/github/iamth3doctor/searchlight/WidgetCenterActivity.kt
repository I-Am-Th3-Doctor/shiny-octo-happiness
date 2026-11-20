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
import io.github.iamth3doctor.searchlight.ui.CollapsibleSections
import io.github.iamth3doctor.searchlight.ui.MaxWidthLinearLayout
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.edgeToEdge
import io.github.iamth3doctor.searchlight.ui.isLightTheme
import io.github.iamth3doctor.searchlight.ui.padForSystemBars
import io.github.iamth3doctor.searchlight.ui.pickWeatherApp
import io.github.iamth3doctor.searchlight.ui.wrappingItems
import io.github.iamth3doctor.searchlight.ui.wrappingSingleChoice
import io.github.iamth3doctor.searchlight.ui.primaryText
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.secondaryText
import io.github.iamth3doctor.searchlight.ui.type
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
        content = MaxWidthLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(R.dimen.space_m), 0, px(R.dimen.space_m), px(R.dimen.space_l))
            maxWidthPx = resources.getDimensionPixelSize(R.dimen.content_max_width)
        }
        // Centered, capped column like the rest of the app, so it reads well unfolded.
        val scroll = ScrollView(this).apply {
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        setContentView(scroll)
        edgeToEdge(lightBackground = isLightTheme())
        scroll.padForSystemBars()
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
        content.addView(
            TextView(this, null, 0, R.style.PageTitle).apply { setText(R.string.widget_center) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        section(R.string.wc_section_cards, "cards", first = true)
        help(R.string.wc_cards_help)
        val order = prefs.cardOrder
        val off = prefs.disabledCards
        order.forEachIndexed { i, type -> cardRow(type, i, order, type !in off) }

        section(R.string.wc_section_idle, "idle")
        val enabled = prefs.enabledCards
        row(getString(R.string.wc_idle_card), getString(prefs.idleCard.title)) { pickIdleCard() }
        switchRow(R.string.wc_music_front, prefs.musicAutoFront) { prefs.musicAutoFront = it }
        if (enabled.isEmpty()) help(R.string.wc_no_cards)

        section(R.string.wc_section_weather, "weather")
        val weatherApp = prefs.weatherAppKey?.let { app.apps.find(it) }
        row(getString(R.string.weather_app), weatherApp?.label ?: getString(R.string.weather_app_unset)) {
            pickWeatherApp { render() }
        }
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
        // Each section folds open and shut under its title (state kept across re-renders and restarts).
        CollapsibleSections.apply(content, SCREEN, prefs)
    }

    // ------------------------------------------------------------------ cards

    private fun cardRow(type: CardType, index: Int, order: List<CardType>, on: Boolean) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(R.dimen.size_row)
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(this).apply {
            setText(type.title)
            type(Type.BODY)
            isChecked = on
            setOnCheckedChangeListener { _, checked ->
                prefs.disabledCards = if (checked) prefs.disabledCards - type else prefs.disabledCards + type
                render()
            }
        }
        texts.addView(sw)
        texts.addView(TextView(this).apply {
            setText(type.description)
            type(Type.CAPTION)
            setTextColor(secondaryText())
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
            .wrappingSingleChoice(choices.map { getString(it.title) }, choices.indexOf(prefs.idleCard)) { d, which ->
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
                type(Type.BODY)
                isChecked = prefs.tempUnit == unit
                setPadding(px(R.dimen.space_xs), 0, px(R.dimen.space_l), 0)
                setOnClickListener { prefs.tempUnit = unit }
            })
        }
        content.addView(TextView(this).apply {
            setText(R.string.wc_units)
            type(Type.BODY)
            setTextColor(primaryText())
            setPadding(0, px(R.dimen.space_s), 0, 0)
        })
        content.addView(group)
    }

    private fun pickLocation() {
        AlertDialog.Builder(this)
            .setTitle(R.string.wc_location)
            .wrappingItems(listOf(getString(R.string.wc_use_device_location), getString(R.string.wc_search_place))) { _, which ->
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
            setPadding(px(R.dimen.space_l), px(R.dimen.space_s), px(R.dimen.space_l), 0)
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
                            .wrappingItems(places.map { it.label }) { _, which ->
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

    private fun section(text: Int, key: String, first: Boolean = false) {
        content.addView(TextView(this, null, 0, R.style.SettingsSection).apply {
            setText(text)
            tag = CollapsibleSections.headerTag(key)
            if (first) setPadding(paddingLeft, px(R.dimen.space_xs), paddingRight, paddingBottom)
        })
    }

    private fun help(text: Int) {
        content.addView(TextView(this).apply {
            setText(text)
            type(Type.CAPTION)
            setTextColor(secondaryText())
            setPadding(0, 0, 0, px(R.dimen.space_xs))
        })
    }

    private fun row(title: String, summary: String, onClick: () -> Unit) {
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            minimumHeight = px(R.dimen.size_row)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, px(R.dimen.space_s), 0, px(R.dimen.space_s))
            background = getDrawable(resolveAttr(android.R.attr.selectableItemBackground))
            setOnClickListener { onClick() }
            addView(TextView(context).apply { text = title; type(Type.BODY); setTextColor(primaryText()) })
            addView(TextView(context).apply { text = summary; type(Type.CAPTION); setTextColor(secondaryText()) })
        })
    }

    private fun switchRow(text: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
        content.addView(Switch(this).apply {
            setText(text)
            type(Type.BODY)
            minHeight = px(R.dimen.size_row)
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
            layoutParams = LinearLayout.LayoutParams(px(R.dimen.size_touch), px(R.dimen.size_touch))
            setOnClickListener { onClick() }
        }

    private fun resolveAttr(attr: Int): Int {
        val out = android.util.TypedValue()
        theme.resolveAttribute(attr, out, true)
        return out.resourceId
    }


    private companion object {
        const val SCREEN = "widget_center"
        const val REQUEST_LOCATION = 1
    }
}
