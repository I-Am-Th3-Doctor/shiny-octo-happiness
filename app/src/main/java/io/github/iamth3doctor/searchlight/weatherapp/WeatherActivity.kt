package io.github.iamth3doctor.searchlight.weatherapp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.edgeToEdge
import io.github.iamth3doctor.searchlight.ui.isLightTheme
import io.github.iamth3doctor.searchlight.ui.padForSystemBars
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.NwsAlerts
import io.github.iamth3doctor.searchlight.weather.WeatherRepository

/**
 * Searchlight's weather screen: the conditions. The place, the temperature and condition now, the
 * day's summary, the precipitation card, the metric cards, the hours and the 7 days. Opened by the
 * Weather tile and the Weather widget alike.
 *
 * The place name at the top opens your locations; a severe-weather alert shows as a pill beside
 * it, opening the alert in full. Each of those is a screen of its own; back returns here, and back
 * from here closes the weather.
 */
class WeatherActivity : Activity() {

    private val repo: WeatherRepository get() = searchlight.weather

    lateinit var panel: WeatherDataPanel
        private set
    lateinit var offlineGlyph: ImageView
        private set
    /** The place, at the top of the conditions: opens the locations. */
    lateinit var placeButton: TextView
        private set
    /** A severe-weather alert in effect: a pill by the place, opening it in full. */
    lateinit var alertPill: TextView
        private set
    lateinit var places: PlacesView
        private set

    /** The conditions: the screen the weather opens on, and goes back to. */
    lateinit var dataScreen: SwipeFrame
        private set
    lateinit var alertScreen: SwipeFrame
        private set
    lateinit var placesScreen: SwipeFrame
        private set
    lateinit var screens: ScreenStack
        private set

    lateinit var dataScroll: ScrollView
        private set
    private var lastScroll: ScrollView? = null

    private lateinit var alertList: LinearLayout

    /** The hour the precipitation card was dragged to (its own graph); null: now. */
    var precipScrubTime: Long? = null
        private set

    /** Now, in ms (tests may pin it). */
    var clock: () -> Long = System::currentTimeMillis

    /** The conditions showing (nothing over them). */
    val dataShown: Boolean get() = screens.top === dataScreen
    val alertShown: Boolean get() = screens.top === alertScreen
    val placesShown: Boolean get() = screens.top === placesScreen

    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            render()
            main.postDelayed(this, TICK_MS)
        }
    }

    private var light = false
    private val fg get() = if (light) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt()
    private val dim get() = if (light) 0xA61C1C21.toInt() else color(R.color.on_surface_dim)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        light = isLightTheme()
        edgeToEdge(lightBackground = false)

        val root = FrameLayout(this)

        // The conditions: on a dark page, light-on-dark.
        panel = WeatherDataPanel(this, light = false).apply { clock = { this@WeatherActivity.clock() } }
        panel.precip.onScrub = { t ->
            precipScrubTime = t
            panel.precip.update(repo.state.data, clock(), t)
            armIdle()
        }
        panel.precip.onPicked = { armIdle() }
        dataScreen = screen(buildPlace(), buildHeaderEnd(), panel.body, "weather_screen_data", dark = true)
        dataScroll = lastScroll!!
        root.addView(dataScreen, match())

        alertList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alertScreen = screen(title(getString(R.string.weather_alert_title)), null, alertList, "weather_screen_alert")

        places = PlacesView(this, light, repo).apply { onPicked = { screens.pop(); render() } }
        placesScreen = screen(title(getString(R.string.weather_places_title)), null, places.root, "weather_screen_places")

        for (s in listOf(alertScreen, placesScreen)) {
            s.visibility = View.GONE
            root.addView(s, match())
        }
        screens = ScreenStack(dataScreen) { onScreen(it) }

        setContentView(root)
        current = this
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        panel.render(repo.state)
        panelState = repo.state
        onScreen(dataScreen)
        render()
        if (intent.getBooleanExtra(EXTRA_ALERT, false)) showAlerts()
    }

    /** The place ("Tacoma ▾"), opening the locations. */
    private fun buildPlace(): TextView = title("").apply {
        setTextColor(0xFFFFFFFF.toInt())
        tag = "weather_place_button"
        background = GradientDrawable().apply { cornerRadius = 999f; setColor(0x00000000) }
        setOnClickListener { openPlaces() }
        placeButton = this
    }

    /** By the place: the offline glyph (just that, offline) and an alert's pill. */
    private fun buildHeaderEnd(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        offlineGlyph = ImageView(this).apply {
            setImageResource(R.drawable.ic_cloud_off)
            alpha = 0.8f
            contentDescription = getString(R.string.weather_offline_desc)
            visibility = View.GONE
        }
        val g = px(R.dimen.space_m) + px(R.dimen.space_xs)
        box.addView(offlineGlyph, LinearLayout.LayoutParams(g, g).apply { marginEnd = px(R.dimen.space_s) })
        alertPill = TextView(this).apply {
            type(Type.CAPTION)
            typeface = Typeface.create(typeface, 700, false)
            letterSpacing = 0.08f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val p = px(R.dimen.space_s)
            setPadding(p * 2, p / 2, p * 2, p / 2)
            setTextColor(color(R.color.on_gold))
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(color(R.color.gold)) }
            visibility = View.GONE
            tag = "weather_alert_pill"
            setOnClickListener { showAlerts() }
        }
        box.addView(alertPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return box
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    // ------------------------------------------------------------------ the screens

    private fun title(text: String) = TextView(this).apply {
        type(Type.TILE_TITLE)
        typeface = Typeface.create(typeface, 700, false)
        setTextColor(fg)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        this.text = text
    }

    /**
     * A screen of its own: opaque, a header (back, [titleView], [action]), then [content] scrolling under it.
     * [dark]: the dark page whatever the theme (the conditions), light-on-dark.
     */
    private fun screen(titleView: TextView, action: View?, content: View, tag: String, dark: Boolean = false): SwipeFrame {
        val frame = SwipeFrame(this).apply {
            this.tag = tag
            isClickable = true
            val l = light && !dark
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                color(if (l) R.color.settings_page_light_top else R.color.settings_page_dark_top),
                color(if (l) R.color.settings_page_light_bottom else R.color.settings_page_dark_bottom),
            ))
        }
        val ink = if (dark) 0xFFFFFFFF.toInt() else fg
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val m = px(R.dimen.space_m)
        val button = px(R.dimen.size_cal_chevron) + px(R.dimen.space_xs)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(m - px(R.dimen.space_s), m / 2, m - px(R.dimen.space_s), m / 2)
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_arrow_down)
            setColorFilter(ink)
            val p = px(R.dimen.space_s)
            setPadding(p, p, p, p)
            contentDescription = getString(R.string.weather_back)
            this.tag = "weather_screen_back"
            setOnClickListener { back() }
        }, LinearLayout.LayoutParams(button, button))
        header.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(R.dimen.space_s) })
        if (action != null) header.addView(action)
        col.addView(header)
        val scroll = TopScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(m, 0, m, m)
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        col.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        lastScroll = scroll
        frame.addView(col, match())
        col.padForSystemBars()
        // Swipe down (from the top of the list) to go back.
        frame.onSwipeDown = { back() }
        frame.canSwipeDown = { !scroll.canScrollVertically(-1) }
        return frame
    }

    /** A screen came in: status bar icons to suit it; anything left playing on the way out stops. */
    private fun onScreen(v: View) {
        // The conditions are always dark; the others follow the theme.
        val dark = v === dataScreen
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark && light
            isAppearanceLightNavigationBars = !dark && light
        }
        armIdle()
    }

    /** Back to the conditions, from whatever is over them. */
    fun openDetails() {
        if (screens.depth > 0) screens.clear()
    }

    fun openPlaces() {
        places.render()
        screens.push(placesScreen)
    }

    /** Back: the screen on top, then the weather closes. */
    fun back() {
        if (screens.depth > 0) screens.pop() else finish()
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        repo.addListener(listener)
        render()
        // Opening the screen is a refresh (the last good reading shows meanwhile, and stays offline).
        repo.refresh()
        main.postDelayed(tick, TICK_MS)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        armIdle()
        render()
    }

    override fun onPause() {
        resumed = false
        disarmIdle()
        super.onPause()
    }

    override fun onStop() {
        repo.removeListener(listener)
        main.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    @Deprecated("Back closes the screen on top first")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = back()

    private var panelState: WeatherRepository.State? = null

    fun render() {
        if (!::screens.isInitialized) return
        val state = repo.state
        val data = state.data
        val now = clock()
        offlineGlyph.visibility = if (data != null && state.problem == WeatherRepository.Problem.NETWORK) View.VISIBLE else View.GONE
        val place = data?.locationName ?: repo.city?.name ?: repo.homeName() ?: getString(R.string.weather_places_home_unknown)
        placeButton.text = "$place  ▾"
        placeButton.contentDescription = getString(R.string.weather_places_desc, place)

        // Alerts: a pill only while one is in effect; nothing at all otherwise.
        val alerts = NwsAlerts.active(data?.alerts, now)
        if (alerts.isEmpty()) {
            alertPill.visibility = View.GONE
            if (alertShown) screens.pop()
        } else {
            alertPill.visibility = View.VISIBLE
            alertPill.text = NwsAlerts.pill(alerts.first()) + if (alerts.size > 1) "  +${alerts.size - 1}" else ""
            alertPill.contentDescription = getString(R.string.weather_alert_pill_desc, alerts.first().event)
        }

        // The conditions rebuild when the reading changes.
        if (state !== panelState) {
            panel.render(state)
            panelState = state
        }
        panel.precip.update(data, now, precipScrubTime)
    }

    // ------------------------------------------------------------------ alerts

    /** The alerts in effect, in full, on their own screen: event, headline, when, what's happening, what to do. */
    fun showAlerts() {
        val data = repo.state.data ?: return
        val alerts = NwsAlerts.active(data.alerts, clock())
        if (alerts.isEmpty()) return
        alertList.removeAllViews()
        val accent = color(if (light) R.color.accent else R.color.gold)
        val zone = java.time.ZoneOffset.ofTotalSeconds(data.detail?.utcOffsetSec ?: 0)
        val fmt = java.time.format.DateTimeFormatter.ofPattern(
            if (android.text.format.DateFormat.is24HourFormat(this)) "EEE HH:mm" else "EEE h:mm a", resources.configuration.locales[0])
        fun at(ms: Long?) = ms?.let { fmt.format(java.time.Instant.ofEpochMilli(it).atOffset(zone)) }
        fun text(style: Int, color: Int, weight: Int = 400, value: CharSequence) = TextView(this).apply {
            type(style)
            if (weight != 400) typeface = Typeface.create(typeface, weight, false)
            setTextColor(color)
            text = value
            setLineSpacing(0f, 1.15f)
        }
        fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
        alerts.forEachIndexed { i, a ->
            val top = if (i == 0) 0 else px(R.dimen.space_l)
            alertList.addView(text(Type.CAPTION, accent, 700, NwsAlerts.pill(a)).apply { letterSpacing = 0.08f; tag = "alert_pill" }, lp(top))
            alertList.addView(text(Type.TILE_TITLE, fg, 700, a.event).apply { tag = "alert_event" }, lp(px(R.dimen.space_xs)))
            if (a.headline.isNotBlank()) alertList.addView(text(Type.BODY, fg, 600, a.headline).apply { tag = "alert_headline" }, lp(px(R.dimen.space_xs)))
            val whenText = listOfNotNull(at(a.effective)?.let { getString(R.string.weather_alert_effective, it) },
                at(a.expires)?.let { getString(R.string.weather_alert_expires, it) }).joinToString("  ·  ")
            if (whenText.isNotBlank()) alertList.addView(text(Type.CAPTION, dim, 600, whenText).apply { tag = "alert_times" }, lp(px(R.dimen.space_xs)))
            if (a.description.isNotBlank()) alertList.addView(text(Type.BODY, fg, value = a.description).apply { tag = "alert_description" }, lp(px(R.dimen.space_s)))
            if (a.instruction.isNotBlank()) {
                alertList.addView(text(Type.CAPTION, accent, 700, getString(R.string.weather_alert_what_to_do).uppercase())
                    .apply { letterSpacing = 0.08f }, lp(px(R.dimen.space_s)))
                alertList.addView(text(Type.BODY, fg, value = a.instruction).apply { tag = "alert_instruction" }, lp(px(R.dimen.space_xs)))
            }
            if (a.sender.isNotBlank()) alertList.addView(text(Type.CAPTION, dim, value = a.sender), lp(px(R.dimen.space_s)))
        }
        screens.push(alertScreen)
    }

    fun hideAlerts() {
        if (alertShown) screens.pop()
    }

    // ------------------------------------------------------------------ back to now on its own

    private var resumed = false
    private var touching = false

    /** When (uptime) the screen goes back to now on its own, if it's left alone till then; null: not counting. */
    var idleReturnAt: Long? = null
        private set

    /** Showing anything but now: the precip card on another day or hour. */
    val awayFromNow: Boolean
        get() = panel.precip.awayFromToday || precipScrubTime != null

    /** A finger is down: the idle count waits for it to lift. */
    private val idleBusy: Boolean
        get() = touching

    private val idleReturn = Runnable {
        idleReturnAt = null
        returnToNow()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val a = ev.actionMasked
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            touching = true
            disarmIdle()
        }
        val handled = super.dispatchTouchEvent(ev)
        if (a == android.view.MotionEvent.ACTION_UP || a == android.view.MotionEvent.ACTION_CANCEL) {
            touching = false
            armIdle()
        }
        return handled
    }

    /** (Re)starts the count to going back to now: from this moment, if away from now and nothing is held. */
    fun armIdle() {
        disarmIdle()
        if (!resumed || !::panel.isInitialized || !awayFromNow || idleBusy) return
        val ms = idleMsOverride ?: IDLE_MS
        idleReturnAt = android.os.SystemClock.uptimeMillis() + ms
        main.postDelayed(idleReturn, ms)
    }

    private fun disarmIdle() {
        main.removeCallbacks(idleReturn)
        idleReturnAt = null
    }

    /** Back to now: the precipitation card to now and today. */
    fun returnToNow() {
        if (!awayFromNow || idleBusy) return
        if (panel.precip.awayFromToday) panel.precip.backToToday()
        if (precipScrubTime != null) {
            precipScrubTime = null
            panel.precip.update(repo.state.data, clock(), null)
        }
        disarmIdle()
    }

    companion object {
        private const val TICK_MS = 30_000L
        /** Left alone this long away from now, the screen goes back to now on its own. */
        const val IDLE_MS = 5_000L

        /** Tests: how long before going back to now (null: [IDLE_MS]). */
        @Volatile var idleMsOverride: Long? = null

        /** The screen that's open (tests). */
        @Volatile var current: WeatherActivity? = null
            private set

        /** Opens straight to the severe-weather alert's details. */
        const val EXTRA_ALERT = "alert"

        fun open(context: Context, alert: Boolean = false) {
            context.startActivity(Intent(context, WeatherActivity::class.java).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (alert) putExtra(EXTRA_ALERT, true)
            })
        }
    }
}
