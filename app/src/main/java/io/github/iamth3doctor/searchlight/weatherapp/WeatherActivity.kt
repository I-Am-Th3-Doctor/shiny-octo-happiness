package io.github.iamth3doctor.searchlight.weatherapp

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
import io.github.iamth3doctor.searchlight.weather.DayBar
import io.github.iamth3doctor.searchlight.weather.NwsAlerts
import io.github.iamth3doctor.searchlight.weather.WeatherCodes
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherSkin
import kotlin.math.roundToInt

/**
 * Searchlight's weather screen: the conditions. The place, the temperature and condition now, the
 * day's summary, the precipitation card, the hours and the 7 days, over a dimmed living scene of
 * the weather (drag across it to go through the hours; hold a day to play it). Opened by the
 * Weather widget, and by the Weather tile when no weather app of your own can be opened.
 *
 * The place name at the top opens your locations; a severe-weather alert shows as a pill beside
 * it, opening the alert in full. Each of those is a screen of its own; back returns here, and back
 * from here closes the weather. (The full-screen "hero" scene is not part of Searchlight: it's for
 * a second launcher.)
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

    /**
     * The conditions' living scene, behind its numbers (dimmed by [dataScrim]): the weather now, an
     * hour dragged to across the screen, or a held 7 DAYS row's day playing full-screen.
     */
    lateinit var detailLapse: DayLapseWindow
        private set
    lateinit var dataScrim: View
        private set
    /** The hour dragged to on the conditions' scene; null: now. */
    var detailScrubTime: Long? = null
        private set
    /** Whether a finger is dragging the conditions' scene through the hours. */
    val detailScrubbing: Boolean get() = ::dataScreen.isInitialized && dataScreen.draggingX
    private var detailScrubBase = DayBar.BEFORE_HOURS
    private val HOUR_MS = 3_600_000L
    lateinit var dataScroll: ScrollView
        private set
    /** Where the details were scrolled to when a day was held: let go, they go back there. */
    private var lapseReturnY: Int? = null
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

    /** The scene's colors: Searchlight's own (the skins belong to the second launcher's hero). */
    private val skin = WeatherSkin.GOLD
    private var light = false
    private val fg get() = if (light) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt()
    private val dim get() = if (light) 0xA61C1C21.toInt() else color(R.color.on_surface_dim)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        light = isLightTheme()
        edgeToEdge(lightBackground = false)

        val root = FrameLayout(this)

        // The conditions: over a living scene, so always light-on-dark.
        panel = WeatherDataPanel(this, light = false).apply { clock = { this@WeatherActivity.clock() } }
        panel.precip.onScrub = { t ->
            precipScrubTime = t
            panel.precip.update(repo.state.data, clock(), t)
            armIdle()
        }
        panel.precip.onPicked = { armIdle() }
        panel.onHoldDay = { i -> startDetailLapse(i) }
        panel.onReleaseDay = { stopDetailLapse() }
        panel.heldDay = { detailLapse.day }
        detailLapse = DayLapseWindow(this, card = false).apply {
            preview.tag = "weather_data_scene"
            // The details' own big number and condition play along with the day.
            onFrame = { c, r -> panel.showMoment(c.tempC, WeatherCodes.describe(r.code)) }
        }
        dataScreen = screen(buildPlace(), buildHeaderEnd(), panel.body, "weather_screen_data", backdrop = detailLapse)
        dataScroll = lastScroll!!
        // Drag sideways across the conditions: the scene behind goes through the hours (the hourly
        // strip and the precip card keep their own sideways drags).
        dataScreen.canDragXAt = { x, y ->
            detailScrubBase = detailScrubHour()
            detailLapse.day == null && !overOwnDrag(dataScreen, x, y)
        }
        dataScreen.onDragX = { dx -> scrubDetails(dx) }
        dataScreen.onDragXEnd = { armIdle() }
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
        detailLapse.showLive(repo.state.data, clock())
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
     * With a [backdrop], the content scrolls over its living scene (dimmed), light-on-dark.
     */
    private fun screen(titleView: TextView, action: View?, content: View, tag: String, backdrop: DayLapseWindow? = null): SwipeFrame {
        val frame = SwipeFrame(this).apply {
            this.tag = tag
            isClickable = true
            background = if (backdrop != null) GradientDrawable().apply { setColor(0xFF000000.toInt()) }
            else GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
                color(if (light) R.color.settings_page_light_top else R.color.settings_page_dark_top),
                color(if (light) R.color.settings_page_light_bottom else R.color.settings_page_dark_bottom),
            ))
        }
        val ink = if (backdrop != null) 0xFFFFFFFF.toInt() else fg
        if (backdrop != null) {
            frame.addView(backdrop.preview, match())
            // Dimmed, so the numbers read over any sky.
            dataScrim = View(this).apply { setBackgroundColor(if (skin.light) 0xB8000000.toInt() else 0x8C000000.toInt()); this.tag = "weather_data_scrim" }
            frame.addView(dataScrim, match())
        }
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
        // The conditions sit over their scene: always dark. The others follow the theme.
        val dark = v === dataScreen
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark && light
            isAppearanceLightNavigationBars = !dark && light
        }
        if (v !== dataScreen) { stopDetailLapse(animate = false); endDetailScrub() }
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
        stopDetailLapse(animate = false)
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

        // The conditions rebuild when the reading changes; the scene behind shows now (unless a day is playing or an hour is dragged to).
        if (state !== panelState) {
            panel.render(state)
            panelState = state
        }
        if (detailScrubTime == null) detailLapse.showLive(data, now)
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

    // ------------------------------------------------------------------ the details' time-lapse

    private var lapseScroll: Runnable? = null
    private val lapseEase = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)

    /**
     * Holding day [i] in the details' 7 DAYS: up to the top of the details (the big number, the day,
     * the precip card), and the day plays full-screen in the scene behind them, the number and
     * condition following it.
     */
    fun startDetailLapse(i: Int) {
        val data = repo.state.data ?: return
        detailLapse.day?.let { panel.highlightDay(it, false) }
        if (!detailLapse.play(data, i)) return
        panel.highlightDay(i, true)
        if (soundOn()) panel.dayRows.getOrNull(i)?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        disarmIdle()
        detailScrubTime = null
        if (lapseReturnY == null) lapseReturnY = dataScroll.scrollY
        dataScrim.animate().alpha(LAPSE_SCRIM).setDuration(LAPSE_SCROLL_MS).start()
        scrollDetails(0, animate = true)
    }

    /** Let go: the day stops, the scene eases back to now, and the details go back exactly as they were. */
    fun stopDetailLapse(animate: Boolean = true) {
        val i = detailLapse.day
        val y = lapseReturnY
        if (i == null && y == null) return
        detailLapse.stop(repo.state.data, clock())
        i?.let { panel.highlightDay(it, false) }
        panel.showMoment(null, null)
        dataScrim.animate().cancel()
        if (animate) dataScrim.animate().alpha(1f).setDuration(LAPSE_SCROLL_MS).start() else dataScrim.alpha = 1f
        lapseReturnY = null
        if (y != null) scrollDetails(y, animate)
    }

    /** A sideways drag [dx] on the data screen: the scene behind goes to that hour, the big number with it. */
    private fun scrubDetails(dx: Float) {
        val data = repo.state.data ?: return
        val now = clock()
        val start = DayBar.start(now)
        val step = (dataScreen.width / DayBar.HOURS.toFloat()).coerceAtLeast(18 * resources.displayMetrics.density)
        val hour = (detailScrubBase + (dx / step).roundToInt()).coerceIn(0, DayBar.HOURS)
        val t = start + hour * HOUR_MS
        if (t == detailScrubTime) return
        disarmIdle()
        detailScrubTime = t
        detailLapse.showAt(data, t)
        if (soundOn()) dataScreen.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Where a drag starts on the data screen: from the hour it's on. */
    private fun detailScrubHour(): Int =
        detailScrubTime?.let { ((it - DayBar.start(clock())) / HOUR_MS).toInt().coerceIn(0, DayBar.HOURS) } ?: DayBar.BEFORE_HOURS

    /** The data screen's scene back to now, easing there, and its big number with it. */
    private fun endDetailScrub() {
        if (detailScrubTime == null) return
        detailScrubTime = null
        detailLapse.showLive(repo.state.data, clock())
        panel.showMoment(null, null)
    }

    /** Whether ([x], [y] in [frame]) is over something with a sideways drag of its own (the hourly strip, the precip card). */
    private fun overOwnDrag(frame: View, x: Float, y: Float): Boolean {
        val o = IntArray(2).also { frame.getLocationOnScreen(it) }
        val sx = o[0] + x.roundToInt()
        val sy = o[1] + y.roundToInt()
        fun hit(v: View): Boolean {
            if (!v.isShown) return false
            val p = IntArray(2).also { v.getLocationOnScreen(it) }
            return sx >= p[0] && sx < p[0] + v.width && sy >= p[1] && sy < p[1] + v.height
        }
        fun walk(v: View): Boolean {
            if (!hit(v)) return false
            if (v === panel.precip.root || v is android.widget.HorizontalScrollView) return true
            return v is ViewGroup && (0 until v.childCount).any { walk(v.getChildAt(it)) }
        }
        return walk(panel.body)
    }

    /** Whether the details are scrolling (into a held day, or back from one). */
    val detailsScrolling: Boolean get() = lapseScroll != null

    private fun scrollDetails(to: Int, animate: Boolean) {
        lapseScroll?.let { dataScroll.removeCallbacks(it) }
        lapseScroll = null
        val from = dataScroll.scrollY
        if (!animate || from == to || !android.animation.ValueAnimator.areAnimatorsEnabled()) {
            dataScroll.scrollTo(0, to)
            return
        }
        val start = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val f = ((android.os.SystemClock.uptimeMillis() - start).toFloat() / LAPSE_SCROLL_MS).coerceIn(0f, 1f)
                dataScroll.scrollTo(0, from + ((to - from) * lapseEase.getInterpolation(f)).roundToInt())
                if (f < 1f) dataScroll.postOnAnimation(this) else lapseScroll = null
            }
        }
        lapseScroll = step
        dataScroll.postOnAnimation(step)
    }

    // ------------------------------------------------------------------ back to now on its own

    private var resumed = false
    private var touching = false

    /** When (uptime) the screen goes back to now on its own, if it's left alone till then; null: not counting. */
    var idleReturnAt: Long? = null
        private set

    /** Showing anything but now: an hour dragged to, the precip card on another day or hour, a day playing. */
    val awayFromNow: Boolean
        get() = panel.precip.awayFromToday || precipScrubTime != null || detailLapse.day != null || detailScrubTime != null

    /** A finger is down, holding or dragging: the idle count waits for it to lift. */
    private val idleBusy: Boolean
        get() = touching || detailLapse.day != null || detailScrubbing

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

    /** Back to now: the precipitation card to now and today, the scene to now. */
    fun returnToNow() {
        if (!awayFromNow || idleBusy) return
        if (panel.precip.awayFromToday) panel.precip.backToToday()
        if (precipScrubTime != null) {
            precipScrubTime = null
            panel.precip.update(repo.state.data, clock(), null)
        }
        endDetailScrub()
        disarmIdle()
    }

    // ------------------------------------------------------------------ haptics

    /** Silent, vibrate or Do Not Disturb: no haptics. */
    fun systemQuiet(): Boolean {
        quietOverride?.let { return it }
        val audio = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        val ringerQuiet = audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return ringerQuiet || dnd
    }

    /** Haptics (a tick per hour dragged, a tap holding a day): on unless turned off, and never while the phone is silenced. */
    fun soundOn(): Boolean = searchlight.prefs.weatherSound && !systemQuiet()

    companion object {
        private const val TICK_MS = 30_000L
        /** Left alone this long away from now, the screen goes back to now on its own. */
        const val IDLE_MS = 5_000L
        private const val LAPSE_SCROLL_MS = 420L
        /** How dim the scene is behind the details while a day plays (of the usual): a little clearer. */
        private const val LAPSE_SCRIM = 0.7f

        /** Tests: how long before going back to now (null: [IDLE_MS]). */
        @Volatile var idleMsOverride: Long? = null

        /** Tests: stand in for silent / Do Not Disturb (null: the real state). */
        @Volatile var quietOverride: Boolean? = null

        /** The screen that's open (tests). */
        @Volatile var current: WeatherActivity? = null
            private set

        /** Opens straight to the severe-weather alert's details. */
        const val EXTRA_ALERT = "alert"

        /** Opens the conditions (they're where the weather always opens; kept for callers that ask). */
        const val EXTRA_DETAILS = "details"

        fun open(context: Context, alert: Boolean = false, details: Boolean = false) {
            context.startActivity(Intent(context, WeatherActivity::class.java).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (alert) putExtra(EXTRA_ALERT, true)
                if (details) putExtra(EXTRA_DETAILS, true)
            })
        }
    }
}
