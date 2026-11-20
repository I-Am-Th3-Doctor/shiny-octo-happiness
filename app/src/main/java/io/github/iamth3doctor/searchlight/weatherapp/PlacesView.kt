package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.GeoPlace
import io.github.iamth3doctor.searchlight.weather.WeatherRepository

/**
 * Locations: search any city (Open-Meteo's free geocoding, the same source as the forecast), save
 * it, switch between saved cities and home. Picking one shows it everywhere (this screen, the
 * widget and the Weather tile), so they always agree; home (Tacoma) is always first and is the default.
 */
class PlacesView(private val context: Context, private val light: Boolean, private val repo: WeatherRepository) {

    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val search = EditText(context)
    val results = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val saved = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    /** A place was picked (null: home): the screen closes. */
    var onPicked: (() -> Unit)? = null

    private fun px(t: Int) = context.px(t)
    private val fg = if (light) 0xFF1C1C21.toInt() else context.color(R.color.on_surface)
    private val dim = if (light) 0xA61C1C21.toInt() else context.color(R.color.on_surface_dim)
    private val accent = context.color(if (light) R.color.accent else R.color.gold)
    private val inset = if (light) 0x14000000 else context.color(R.color.surface_inset)

    private val resultsLabel = label(R.string.weather_places_results)
    private var query = ""

    init {
        search.apply {
            type(Type.BODY)
            setTextColor(fg)
            setHintTextColor(dim)
            hint = context.getString(R.string.weather_places_search)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            val p = px(R.dimen.space_s) + px(R.dimen.space_xs)
            setPadding(p, p, p, p)
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(inset) }
            setOnEditorActionListener { _, action, event ->
                if (action == EditorInfo.IME_ACTION_SEARCH || event?.keyCode == KeyEvent.KEYCODE_ENTER) { find(text.toString()); true } else false
            }
        }
        root.addView(search, lp())
        resultsLabel.visibility = View.GONE
        root.addView(resultsLabel, lp(px(R.dimen.space_m)))
        root.addView(results, lp(px(R.dimen.space_xs)))
        root.addView(label(R.string.weather_places_saved), lp(px(R.dimen.space_m)))
        saved.background = GradientDrawable().apply { cornerRadius = context.pxF(R.dimen.radius_card) / 2; setColor(inset) }
        root.addView(saved, lp(px(R.dimen.space_xs)))
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }

    private fun label(res: Int) = TextView(context).apply {
        type(Type.CAPTION)
        typeface = Typeface.create(typeface, 700, false)
        letterSpacing = 0.08f
        setTextColor(accent)
        text = context.getString(res).uppercase()
    }

    /** Fills the saved list (home first, the one showing marked). */
    fun render() {
        saved.removeAllViews()
        val current = repo.city
        val homeName = repo.homeName()
        saved.addView(row(
            homeName?.let { context.getString(R.string.weather_places_home, it) } ?: context.getString(R.string.weather_places_home_unknown),
            null, selected = current == null, tag = "weather_place_home", remove = null,
        ) { pick(null) })
        for (p in repo.savedCities) {
            val sel = current != null && WeatherRepository.cityKey(current) == WeatherRepository.cityKey(p)
            saved.addView(row(p.name, p.label.takeIf { it != p.name }, sel, "weather_place_saved", remove = { repo.removeCity(p); render() }) { pick(p) })
        }
    }

    /** Searches for [q] and lists what's found. */
    fun find(q: String) {
        val text = q.trim()
        if (text.length < 2) return
        query = text
        (context.getSystemService(InputMethodManager::class.java))?.hideSoftInputFromWindow(search.windowToken, 0)
        repo.searchPlaces(text) { places ->
            if (query != text) return@searchPlaces
            results.removeAllViews()
            resultsLabel.visibility = View.VISIBLE
            when {
                places == null -> results.addView(note(R.string.weather_places_failed))
                places.isEmpty() -> results.addView(note(R.string.weather_places_none))
                else -> places.forEach { p ->
                    results.addView(row(p.name, p.label, selected = false, tag = "weather_place_result", remove = null) {
                        repo.saveCity(p)
                        pick(p)
                    })
                }
            }
        }
    }

    private fun pick(p: GeoPlace?) {
        repo.selectCity(p)
        search.setText("")
        results.removeAllViews()
        resultsLabel.visibility = View.GONE
        render()
        onPicked?.invoke()
    }

    private fun note(res: Int) = TextView(context).apply {
        type(Type.BODY)
        setTextColor(dim)
        text = context.getString(res)
        val p = px(R.dimen.space_s)
        setPadding(p, p, p, p)
    }

    private fun row(title: String, sub: String?, selected: Boolean, tag: String, remove: (() -> Unit)?, onTap: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(R.dimen.size_cal_chevron) + px(R.dimen.space_s)
            val p = px(R.dimen.space_s) + px(R.dimen.space_xs)
            setPadding(p, px(R.dimen.space_xs), px(R.dimen.space_xs), px(R.dimen.space_xs))
            this.tag = tag
            isClickable = true
            setOnClickListener { onTap() }
            contentDescription = title + (sub?.let { ", $it" } ?: "")
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (selected) accent else 0) }
        }
        val ds = px(R.dimen.space_s)
        row.addView(dot, LinearLayout.LayoutParams(ds, ds).apply { marginEnd = px(R.dimen.space_s) })
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            type(Type.BODY)
            typeface = Typeface.create(typeface, if (selected) 700 else 500, false)
            setTextColor(fg)
            text = title
        })
        if (!sub.isNullOrBlank()) col.addView(TextView(context).apply { type(Type.CAPTION); setTextColor(dim); text = sub })
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (remove != null) {
            val b = px(R.dimen.size_cal_chevron)
            row.addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(dim)
                val p = px(R.dimen.space_s)
                setPadding(p, p, p, p)
                contentDescription = context.getString(R.string.weather_places_remove, title)
                setOnClickListener { remove() }
            }, LinearLayout.LayoutParams(b, b))
        }
        return row
    }
}
