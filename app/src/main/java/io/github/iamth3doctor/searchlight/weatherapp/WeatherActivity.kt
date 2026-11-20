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
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.edgeToEdge
import io.github.iamth3doctor.searchlight.ui.isLightTheme
import io.github.iamth3doctor.searchlight.ui.padForSystemBars
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.DayBar
import io.github.iamth3doctor.searchlight.weather.HeroReadings
import io.github.iamth3doctor.searchlight.weather.WeatherSkin
import io.github.iamth3doctor.searchlight.weather.NwsAlerts
import io.github.iamth3doctor.searchlight.weather.SceneConditions
import io.github.iamth3doctor.searchlight.weather.SceneModel
import io.github.iamth3doctor.searchlight.weather.SceneSound
import io.github.iamth3doctor.searchlight.weather.WeatherCodes
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherRepository
import io.github.iamth3doctor.searchlight.weather.WeatherText
import kotlin.math.roundToInt

/**
 * The weather, as a place: a full-screen living scene of the conditions (sky, sun or moon, clouds,
 * rain, snow, lightning, fog, birds, all moving with the wind), the temperature as a giant number,
 * the day along the bottom to scrub through like a timelapse. Drag the bar, or the number itself,
 * and the scene, number, condition and sound all follow the hour. Tap the scene for the quick
 * numbers over it; swipe up for the 7 days; the precipitation opens the full details; the place
 * name opens locations.
 *
 * Every other view (7 days, details, an alert, locations) is a screen of its own, opaque, that
 * replaces the scene rather than floating over it: one goes out completely before the next comes in.
 */
class WeatherActivity : Activity() {

    private val repo: WeatherRepository get() = searchlight.weather

    lateinit var scene: WeatherSceneView
        private set
    lateinit var bar: DayBarView
        private set
    lateinit var panel: WeatherDataPanel
        private set
    lateinit var muteButton: ImageView
        private set
    lateinit var offlineGlyph: ImageView
        private set
    /** The top-left button: a small cloud; one tap opens the data screen ([openDetails]). */
    lateinit var menuButton: ImageView
        private set
    lateinit var hero: HeroTempView
        private set
    /** The hero: the big number in the scene, the callouts round it, the condition and timeline under. */
    lateinit var heroBox: HeroLayout
        private set
    lateinit var heroCondition: TextView
        private set
    /** The callouts' leader lines, over the scene. */
    lateinit var leaders: LeaderLinesView
        private set
    lateinit var alertPill: TextView
        private set
    lateinit var placeButton: TextView
        private set
    lateinit var weekHandle: View
        private set
    lateinit var scrim: View
        private set
    lateinit var quick: QuickDataView
        private set
    lateinit var week: WeekView
        private set
    lateinit var places: PlacesView
        private set

    /** The scene and everything on it (the "hero" screen). */
    lateinit var heroScreen: SwipeFrame
        private set
    lateinit var weekScreen: SwipeFrame
        private set
    lateinit var dataScreen: SwipeFrame
        private set
    lateinit var alertScreen: SwipeFrame
        private set
    lateinit var placesScreen: SwipeFrame
        private set
    lateinit var screens: ScreenStack
        private set

    /**
     * The data screen's living scene, behind its numbers (dimmed by [dataScrim]): the weather now, an
     * hour dragged to across the screen, or a held 7 DAYS row's day playing full-screen. No bar or
     * readout over it: the scene and the big number show the hour.
     */
    lateinit var detailLapse: DayLapseWindow
        private set
    lateinit var dataScrim: View
        private set
    /** The hour dragged to on the data screen's scene; null: now. */
    var detailScrubTime: Long? = null
        private set
    /** Whether a finger is dragging the data screen's scene through the hours. */
    val detailScrubbing: Boolean get() = ::dataScreen.isInitialized && dataScreen.draggingX
    private var detailScrubBase = DayBar.BEFORE_HOURS
    private val HOUR_MS = 3_600_000L
    lateinit var dataScroll: ScrollView
        private set
    /** Where the details were scrolled to when a day was held: let go, they go back there. */
    private var lapseReturnY: Int? = null
    private var lastScroll: ScrollView? = null

    private lateinit var quickScroll: ScrollView
    private lateinit var alertList: LinearLayout
    private lateinit var dataTitle: TextView

    val sound = WeatherSound()

    /** Now, in ms (tests may pin it). */
    var clock: () -> Long = System::currentTimeMillis

    /** The previewed time, or null: live. */
    val scrubTime: Long? get() = bar.scrubTime

    val quickShown: Boolean get() = quickScroll.visibility == View.VISIBLE
    val dataShown: Boolean get() = screens.top === dataScreen
    val weekShown: Boolean get() = screens.top === weekScreen
    val alertShown: Boolean get() = screens.top === alertScreen
    val placesShown: Boolean get() = screens.top === placesScreen

    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            // Live, the sun and moon move on; scrubbed, nothing changes.
            if (bar.scrubTime == null) render()
            main.postDelayed(this, TICK_MS)
        }
    }

    /** The look: Frost, monochrome frosted glass (the only one). */
    val skin: WeatherSkin = WeatherSkin.DEFAULT
    private val skinned get() = true
    private var light = false
    private val fg get() = if (skinned) skin.ink else if (light) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt()
    private val dim get() = if (skinned) skin.inkDim else if (light) 0xA61C1C21.toInt() else color(R.color.on_surface_dim)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        light = skin.light
        edgeToEdge(lightBackground = false)

        val root = FrameLayout(this)
        heroScreen = buildHero()
        root.addView(heroScreen, match())

        // The other screens: each opaque, each on its own. The details sit over a living scene, so they're always light-on-dark.
        panel = WeatherDataPanel(this, light = true).apply { clock = { this@WeatherActivity.clock() } }
        panel.precip.onScrub = { t -> if (t == null) bar.goLive() else bar.scrubTo(t) }
        panel.precip.onPicked = { armIdle() }
        panel.onHoldDay = { i -> startDetailLapse(i) }
        panel.onReleaseDay = { stopDetailLapse() }
        panel.heldDay = { detailLapse.day }
        detailLapse = DayLapseWindow(this, card = false).apply {
            preview.tag = "weather_data_scene"
            // The details' own big number and condition play along with the day.
            onFrame = { c, r -> panel.showMoment(c.tempC, WeatherCodes.describe(r.code)) }
        }
        dataTitle = title("")
        dataScreen = screen(dataTitle, null, panel.body, "weather_screen_data", backdrop = detailLapse)
        dataScroll = lastScroll!!
        // Drag sideways across the data screen: the scene behind goes through the hours (the hourly
        // strip and the precip card keep their own sideways drags).
        dataScreen.canDragXAt = { x, y ->
            detailScrubBase = detailScrubHour()
            detailLapse.day == null && !overOwnDrag(dataScreen, x, y)
        }
        dataScreen.onDragX = { dx -> scrubDetails(dx) }
        dataScreen.onDragXEnd = { armIdle() }

        week = WeekView(this, light).apply {
            clock = { this@WeatherActivity.clock() }
            onSettled = { armIdle() }
        }
        val details = TextView(this).apply {
            type(Type.BODY)
            typeface = Typeface.create(typeface, 700, false)
            setTextColor(color(if (light) R.color.accent else R.color.gold))
            text = getString(R.string.weather_details)
            gravity = Gravity.CENTER
            val p = px(R.dimen.space_s)
            setPadding(p, p, p, p)
            tag = "weather_week_details"
            setOnClickListener { openDetails() }
        }
        weekScreen = screen(title(getString(R.string.weather_week_title)), details, week.root, "weather_screen_week")

        alertList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alertScreen = screen(title(getString(R.string.weather_alert_title)), null, alertList, "weather_screen_alert")

        places = PlacesView(this, light, repo).apply { onPicked = { screens.pop(); render() } }
        placesScreen = screen(title(getString(R.string.weather_places_title)), null, places.root, "weather_screen_places")


        for (s in listOf(weekScreen, dataScreen, alertScreen, placesScreen)) {
            s.visibility = View.GONE
            root.addView(s, match())
        }
        screens = ScreenStack(heroScreen) { onScreen(it) }

        // No color anywhere: everything on this screen, the living scene included, is drawn in grays.
        root.setLayerType(View.LAYER_TYPE_HARDWARE, Monochrome.paint())
        setContentView(root)
        current = this
        updateSoundButton()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        render()
        // Opened from the widget's alert glyph: straight to the alert.
        if (intent.getBooleanExtra(EXTRA_ALERT, false)) showAlerts()
    }

    private fun match() = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

    // ------------------------------------------------------------------ the hero screen

    private fun buildHero(): SwipeFrame {
        val frame = SwipeFrame(this).apply { tag = "weather_screen_hero" }
        // The scene, cartoon-style: flat, bold, outlined; a few clouds drift in front of the number.
        scene = WeatherSceneView(this, mini = false).apply {
            renderer.cartoon = true
            setOnClickListener { toggleQuick() }
            renderer.onStrike = { main.post { onStrike() } }
        }
        frame.addView(scene, match())
        // Under the quick numbers, the scene dims.
        scrim = View(this).apply { setBackgroundColor(FROSTED_VEIL); visibility = View.GONE }
        frame.addView(scrim, match())
        // The callouts' leader lines: over the scene, under everything on it.
        leaders = LeaderLinesView(this, scene).apply { skin = this@WeatherActivity.skin; tag = "weather_hero_leaders" }
        frame.addView(leaders, match())

        // Top to bottom, never overlapping: the top row, an alert, the hero (or the quick numbers), "7 days", the day.
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val m = px(R.dimen.space_m)
        column.setPadding(m, m, m, m)
        val button = px(R.dimen.size_cal_chevron) + px(R.dimen.space_xs)

        val top = FrameLayout(this)
        val topLeft = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        menuButton = ImageView(this).apply {
            background = chip()
            setImageResource(R.drawable.ic_cloud)
            val p = px(R.dimen.space_s) + px(R.dimen.space_xs) / 2
            setPadding(p, p, p, p)
            contentDescription = getString(R.string.weather_more_desc)
            tag = "weather_menu_button"
            setOnClickListener { openDetails() }
        }
        topLeft.addView(menuButton, LinearLayout.LayoutParams(button, button))
        offlineGlyph = ImageView(this).apply {
            setImageResource(R.drawable.ic_cloud_off)
            alpha = 0.8f
            contentDescription = getString(R.string.weather_offline_desc)
            visibility = View.GONE
        }
        val g = px(R.dimen.space_m) + px(R.dimen.space_xs)
        topLeft.addView(offlineGlyph, LinearLayout.LayoutParams(g, g).apply { marginStart = px(R.dimen.space_s) })
        top.addView(topLeft, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, button, Gravity.START or Gravity.CENTER_VERTICAL))
        placeButton = TextView(this).apply {
            type(Type.BODY)
            typeface = Typeface.create(typeface, 700, false)
            setTextColor(fg)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val p = px(R.dimen.space_s)
            setPadding(p * 2, 0, p * 2, 0)
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(glass()) }
            tag = "weather_place_button"
            setOnClickListener { openPlaces() }
        }
        val side = button * 2 + px(R.dimen.space_s)
        top.addView(placeButton, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, button, Gravity.CENTER)
            .apply { leftMargin = side; rightMargin = side })
        muteButton = ImageView(this).apply {
            background = chip()
            val p = px(R.dimen.space_s) + px(R.dimen.space_xs) / 2
            setPadding(p, p, p, p)
            setOnClickListener { toggleSound() }
        }
        top.addView(muteButton, FrameLayout.LayoutParams(button, button, Gravity.END or Gravity.CENTER_VERTICAL))
        column.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, button))

        // A severe-weather alert, when there is one: a bold pill on its own row.
        alertPill = TextView(this).apply {
            type(Type.BODY)
            typeface = Typeface.create(typeface, 700, false)
            letterSpacing = 0.08f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val p = px(R.dimen.space_s)
            setPadding(p * 2, p, p * 2, p)
            setTextColor(if (skinned) skin.onAccent else if (light) 0xFFFFFFFF.toInt() else color(R.color.on_gold))
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(if (skinned) skin.accent else color(if (light) R.color.accent else R.color.gold)) }
            elevation = px(R.dimen.space_xs).toFloat()
            visibility = View.GONE
            setOnClickListener { showAlerts() }
        }
        column.addView(alertPill, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, button)
            .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = px(R.dimen.space_s) })

        val middle = FrameLayout(this)
        // The hero: the temperature, oversized and dimensional, inside the scene; the callouts round it;
        // the condition in words and the next hours at the bottom.
        hero = HeroTempView(this).apply {
            stepPx = { (bar.width / DayBar.HOURS.toFloat()).coerceAtLeast(18 * resources.displayMetrics.density) }
            onScrubStart = { dragBase = bar.scrubHour ?: DayBar.BEFORE_HOURS }
            onScrubSteps = { steps -> bar.scrubToHour((dragBase + steps).coerceIn(0, DayBar.HOURS)) }
            setOnClickListener { toggleQuick() }
        }
        heroBox = HeroLayout(this, hero, HeroLayout.frontFor(this, scene)).apply {
            tag = "weather_hero"
            style(skin)
        }
        heroCondition = heroBox.condition
        leaders.callouts = heroBox.callouts
        leaders.number = hero
        leaders.floor = heroBox.condition
        // The leader lines follow the scene (the sun moves, the clouds drift) frame by frame.
        val redrawFront = scene.frontRedraw
        scene.frontRedraw = { redrawFront?.invoke(); leaders.invalidate() }
        middle.addView(heroBox, match())

        // The quick numbers: in the hero's place (never over it), over the dimmed scene. Tap to put them away.
        quick = QuickDataView(this).apply { onMore = { openDetails() } }
        quickScroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            addView(quick.root)
            quick.root.setOnClickListener { toggleQuick() }
        }
        middle.addView(quickScroll, match())
        column.addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = px(R.dimen.space_s) })

        // "7 days" (or swipe up), just over the day.
        val handleInk = if (skinned) skin.ink else 0xFFFFFFFF.toInt()
        weekHandle = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            val p = px(R.dimen.space_xs)
            setPadding(p * 3, p, p * 3, p)
            contentDescription = getString(R.string.weather_week_open_desc)
            tag = "weather_week_handle"
            addView(ImageView(this@WeatherActivity).apply { setImageResource(R.drawable.ic_arrow_up); setColorFilter(handleInk) },
                LinearLayout.LayoutParams(px(R.dimen.space_m), px(R.dimen.space_m)))
            addView(TextView(this@WeatherActivity).apply {
                type(Type.CAPTION)
                typeface = Typeface.create(typeface, 700, false)
                letterSpacing = 0.08f
                setTextColor(handleInk)
                if (!skin.light) setShadowLayer(pxF(R.dimen.glass_text_shadow), 0f, 1f, 0x99000000.toInt())
                text = getString(R.string.weather_week_open).uppercase()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = px(R.dimen.space_xs) })
            setOnClickListener { openWeek() }
        }
        column.addView(weekHandle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = px(R.dimen.space_xs) })

        bar = DayBarView(this).apply {
            this.light = this@WeatherActivity.light
            if (skinned) this.skin = this@WeatherActivity.skin
            background = FrostedDrawable(this, pxF(R.dimen.radius_card) / 1.5f, glass(), glass(),
                glassBorder(), pxF(R.dimen.stroke_glass), wallpaper = false)
            onScrub = {
                render()
                if (!returning) armIdle()
            }
        }
        column.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(R.dimen.size_weather_bar)))
        frame.addView(column, match())
        column.padForSystemBars()

        frame.onSwipeUp = { openWeek() }
        frame.canSwipeUp = { !quickShown || !quickScroll.canScrollVertically(1) }
        return frame
    }

    private var dragBase = DayBar.BEFORE_HOURS

    private fun glass() = if (skinned) skin.glass else color(if (light) R.color.settings_glass_light else R.color.settings_glass_dark)

    private fun glassBorder() = if (skinned) SceneRenderer.withAlpha(skin.ink, 0.18)
        else color(if (light) R.color.settings_glass_border_light else R.color.glass_border)

    private fun chip() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(glass())
        setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1), glassBorder())
    }

    // ------------------------------------------------------------------ the other screens

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
        val ink = fg
        if (backdrop != null) {
            frame.addView(backdrop.preview, match())
            // Dimmed like the hero's under its quick numbers, so the numbers read over any sky.
            // Frosted over: a white veil, so the dark numbers read over any sky.
            dataScrim = View(this).apply { setBackgroundColor(FROSTED_VEIL); this.tag = "weather_data_scrim" }
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
        // Frosted throughout: light, so the status and navigation bars' icons are dark.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        if (v !== weekScreen) week.stopPreview()
        if (v !== dataScreen) { stopDetailLapse(animate = false); endDetailScrub() }
        armIdle()
    }

    fun openWeek() {
        if (quickShown) setQuick(false)
        week.haptics = soundOn()
        week.render(repo.state.data)
        weekRendered = repo.state
        screens.push(weekScreen)
    }

    fun openDetails() {
        if (quickShown) setQuick(false)
        if (dataShown) return
        panel.render(repo.state)
        panelState = repo.state
        panel.precip.update(repo.state.data, clock(), bar.scrubTime)
        dataTitle.text = repo.state.data?.locationName ?: getString(R.string.weather_details_title)
        detailScrubTime = null
        detailLapse.showLive(repo.state.data, clock())
        screens.push(dataScreen)
    }

    fun openPlaces() {
        if (quickShown) setQuick(false)
        places.render()
        screens.push(placesScreen)
    }

    /** The details open or closed (from the scene). */
    fun toggleData() {
        if (dataShown) back() else openDetails()
    }

    /** Back: the quick numbers go first, then the screen on top, then the weather. */
    fun back() {
        when {
            screens.depth > 0 -> screens.pop()
            quickShown -> setQuick(false)
            else -> finish()
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        repo.addListener(listener)
        render()
        // Opening the screen is a refresh (the last good scene shows meanwhile, and stays offline).
        repo.refresh()
        main.postDelayed(tick, TICK_MS)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        armIdle()
        updateSoundButton()
        if (soundOn()) sound.start()
        render()
    }

    override fun onPause() {
        resumed = false
        disarmIdle()
        bar.cancelSlide()
        sound.stop()
        week.stopPreview()
        stopDetailLapse(animate = false)
        super.onPause()
    }

    override fun onStop() {
        repo.removeListener(listener)
        main.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        week.stop()
        if (current === this) current = null
        super.onDestroy()
    }

    @Deprecated("Back closes the screen on top first")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = back()

    // ------------------------------------------------------------------ the scene

    /** What the scene shows: the scrubbed time's forecast, or now. */
    fun conditions(): SceneConditions {
        val data = repo.state.data ?: placeholder()
        val t = bar.scrubTime ?: clock()
        return SceneModel.at(data, t, live = bar.scrubTime == null)
    }

    /** Before any reading: a mild day over Tacoma, so the screen is never blank. */
    private fun placeholder() = WeatherData("Tacoma", 47.25, -122.44, 12.0, 2, true, 14.0, 6.0, clock())

    private var panelState: WeatherRepository.State? = null
    private var weekRendered: WeatherRepository.State? = null

    fun render() {
        if (!::bar.isInitialized || !::screens.isInitialized) return
        val state = repo.state
        val now = clock()
        bar.update(state.data, now, bar.scrubTime)
        val c = conditions()
        scene.conditions = c
        menuButton.setColorFilter(fg)
        offlineGlyph.visibility = if (state.data != null && state.problem == WeatherRepository.Problem.NETWORK) View.VISIBLE else View.GONE
        offlineGlyph.setColorFilter(fg)

        // The sound of the hour showing: rain, wind, crickets at night, or quiet; a little softer while scrubbing.
        val levels = SceneSound.levels(c)
        sound.rain = levels.rain
        sound.wind = levels.wind
        sound.crickets = levels.crickets
        sound.volume = if (bar.scrubTime != null) 0.6f else 1f

        // The hero follows the scene: now, or the hour being played.
        val data = state.data
        hero.number = if (data != null) WeatherText.f(c.tempC).toString() else "--"
        heroCondition.text = (if (data != null) WeatherCodes.describe(c.code) else getString(R.string.weather_loading)).uppercase()
        // Round the number: the callouts (each pointing at what it describes), and under the condition the next hours.
        val zone = java.time.ZoneOffset.ofTotalSeconds(data?.detail?.utcOffsetSec ?: 0)
        val tf = WeatherFormat.time(this)
        val hf = WeatherFormat.hour(this)
        for (callout in HeroReadings.callouts(data, c) { tf.format(java.time.Instant.ofEpochMilli(it).atOffset(zone)) }) {
            heroBox.callouts[callout.key]?.show(callout)
        }
        heroBox.timeline.show(HeroReadings.timeline(data, c), { hf.format(java.time.Instant.ofEpochMilli(it).atOffset(zone)) },
            getString(R.string.weather_bar_now).uppercase())
        leaders.windEast = c.windEastMph
        // The sun and moon's callout across the sky from them.
        scene.renderer.bodyPoint()?.let { heroBox.sunOnLeft = it[0] > scene.width / 2f }
        leaders.invalidate()
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

        if (quickShown) quick.render(data, c)
        // The details and 7 days rebuild when the reading changes; scrubbing only moves the precip card along.
        if (dataShown && state !== panelState) {
            panel.render(state)
            panelState = state
            dataTitle.text = data?.locationName ?: getString(R.string.weather_details_title)
        }
        if (weekShown && state !== weekRendered) {
            week.render(data)
            weekRendered = state
        }
        // The 7 days' window and the details' scene show the weather now (unless a day is playing).
        if (weekShown) week.live()
        if (dataShown && detailScrubTime == null) detailLapse.showLive(data, now)
        panel.precip.update(data, now, bar.scrubTime)
        scene.contentDescription = getString(R.string.weather_scene_desc, "${WeatherText.f(c.tempC)}°F", WeatherCodes.describe(c.code))
    }

    /** Tap on the scene: the quick numbers over the dimmed scene, or back to the scene alone. */
    fun toggleQuick() = setQuick(!quickShown)

    /**
     * Straight to the full numbers over the scene (temperature, feels like, cloud, rain chance, wind,
     * humidity, dew point, UV, visibility, sun, moon), from wherever (the scene's tap toggles them).
     */
    fun openQuick() {
        if (screens.depth > 0) screens.clear()
        setQuick(true)
    }

    fun setQuick(show: Boolean) {
        if (show == quickShown) return
        if (show) quick.render(repo.state.data, conditions())
        // The quick numbers take the hero's place: never both.
        heroBox.visibility = if (show) View.GONE else View.VISIBLE
        leaders.visibility = if (show) View.GONE else View.VISIBLE
        quickScroll.visibility = if (show) View.VISIBLE else View.GONE
        scrim.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            scrim.alpha = 0f
            scrim.animate().alpha(1f).setDuration(180).start()
        }
    }

    // ------------------------------------------------------------------ alerts

    /** The alerts in effect, in full, on their own screen: event, headline, when, what's happening, what to do. */
    fun showAlerts() {
        val data = repo.state.data ?: return
        val alerts = NwsAlerts.active(data.alerts, clock())
        if (alerts.isEmpty()) return
        if (quickShown) setQuick(false)
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

    /** Showing anything but now: a scrubbed hour (the bar, the number, the precip graph), another day. */
    val awayFromNow: Boolean
        get() = bar.scrubTime != null || week.awayFromNow || panel.precip.awayFromToday || detailLapse.day != null ||
            detailScrubTime != null

    /** A finger is down, holding or dragging: the idle count waits for it to lift. */
    private val idleBusy: Boolean
        get() = touching || bar.isDragging || hero.scrubbing || week.previewDay != null || detailLapse.day != null || bar.returning ||
            detailScrubbing

    private val idleReturn = Runnable {
        idleReturnAt = null
        returnToNow()
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val a = ev.actionMasked
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            touching = true
            disarmIdle()
            bar.cancelSlide()
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
        if (!resumed || !::bar.isInitialized || !awayFromNow || idleBusy) return
        val ms = idleMsOverride ?: IDLE_MS
        idleReturnAt = android.os.SystemClock.uptimeMillis() + ms
        main.postDelayed(idleReturn, ms)
    }

    private fun disarmIdle() {
        main.removeCallbacks(idleReturn)
        idleReturnAt = null
    }

    /** Back to now, smoothly: the scrubber slides back to NOW with the scene and number following it. */
    fun returnToNow() {
        if (!awayFromNow || idleBusy) return
        if (panel.precip.awayFromToday) panel.precip.backToToday()
        if (bar.scrubTime != null) bar.slideToNow { render() }
        endDetailScrub()
        disarmIdle()
    }

    // ------------------------------------------------------------------ sound and haptics

    /** Silent, vibrate or Do Not Disturb: the screen stays quiet whatever the toggle says. */
    fun systemQuiet(): Boolean {
        quietOverride?.let { return it }
        val audio = getSystemService(AudioManager::class.java)
        val nm = getSystemService(NotificationManager::class.java)
        val ringerQuiet = audio != null && audio.ringerMode != AudioManager.RINGER_MODE_NORMAL
        val filter = nm?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_ALL
        val dnd = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return ringerQuiet || dnd
    }

    /** Sound and haptics: on unless turned off here, and never while the phone is silenced. */
    fun soundOn(): Boolean = searchlight.prefs.weatherSound && !systemQuiet()

    private fun toggleSound() {
        if (systemQuiet()) {
            Toast.makeText(this, R.string.weather_sound_silenced, Toast.LENGTH_SHORT).show()
            updateSoundButton()
            return
        }
        searchlight.prefs.weatherSound = !searchlight.prefs.weatherSound
        updateSoundButton()
        if (soundOn()) sound.start() else sound.stop()
    }

    fun updateSoundButton() {
        val on = soundOn()
        muteButton.setImageResource(if (on) R.drawable.ic_volume else R.drawable.ic_volume_off)
        muteButton.setColorFilter(fg)
        muteButton.contentDescription = getString(if (on) R.string.weather_sound_on else R.string.weather_sound_off)
        bar.haptics = on
    }

    /** Lightning struck in the scene: thunder, and a light tap. */
    var strikesFelt = 0
        private set

    private fun onStrike() {
        if (!soundOn()) return
        strikesFelt++
        sound.thunder()
        scene.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

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

        /** The frosted white veil over the scene behind the quick numbers and the details. */
        const val FROSTED_VEIL = 0xC7F4F5F7.toInt()

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
