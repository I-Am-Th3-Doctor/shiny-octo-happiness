package io.github.iamth3doctor.searchlight.weatherapp

import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.ui.FrostedDrawable
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.edgeToEdge
import io.github.iamth3doctor.searchlight.ui.isLightTheme
import io.github.iamth3doctor.searchlight.ui.padForSystemBars
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type
import io.github.iamth3doctor.searchlight.weather.SceneConditions
import io.github.iamth3doctor.searchlight.weather.SceneModel
import io.github.iamth3doctor.searchlight.weather.WeatherData
import io.github.iamth3doctor.searchlight.weather.WeatherRepository

/**
 * The weather, as a place: a full-screen living scene of the conditions right now (sky, sun or
 * moon, clouds, rain, snow, lightning, fog, all moving with the wind), with the day along the
 * bottom to scrub through like a timelapse. Tap the scene for the numbers; tap again (or Back,
 * or ×) to go back to just the scene. Opened from the Weather widget and the Weather tile.
 */
class WeatherActivity : Activity() {

    private val repo: WeatherRepository get() = searchlight.weather

    lateinit var scene: WeatherSceneView
        private set
    lateinit var bar: DayBarView
        private set
    lateinit var panel: WeatherDataPanel
        private set
    lateinit var dataOverlay: FrameLayout
        private set
    lateinit var muteButton: ImageView
        private set
    lateinit var offlineGlyph: ImageView
        private set
    lateinit var windArrow: WindArrowView
        private set
    lateinit var hero: HeroTempView
        private set
    lateinit var heroCondition: android.widget.TextView
        private set
    lateinit var heroHiLo: android.widget.TextView
        private set
    lateinit var alertPill: android.widget.TextView
        private set
    lateinit var alertOverlay: FrameLayout
        private set
    private lateinit var alertList: LinearLayout

    val alertShown: Boolean get() = alertOverlay.visibility == View.VISIBLE
    val sound = WeatherSound()

    /** Now, in ms (tests may pin it). */
    var clock: () -> Long = System::currentTimeMillis

    /** The previewed time, or null: live. */
    val scrubTime: Long? get() = bar.scrubTime

    val dataShown: Boolean get() = dataOverlay.visibility == View.VISIBLE

    private val main = Handler(Looper.getMainLooper())
    private val listener: () -> Unit = { render() }
    private val tick = object : Runnable {
        override fun run() {
            // Live, the sun and moon move on; scrubbed, nothing changes.
            if (bar.scrubTime == null) render()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val light = isLightTheme()
        edgeToEdge(lightBackground = false)

        val root = FrameLayout(this)
        scene = WeatherSceneView(this, mini = false).apply {
            setOnClickListener { toggleData() }
            renderer.onStrike = { main.post { onStrike() } }
        }
        root.addView(scene, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Chrome, kept small: the wind (and, offline, a small glyph) top left; sound top right; the day at the bottom.
        val chrome = FrameLayout(this)
        val m = px(R.dimen.space_m)
        chrome.setPadding(m, m, m, m)
        val button = px(R.dimen.size_cal_chevron) + px(R.dimen.space_xs)
        val topLeft = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        windArrow = WindArrowView(this).apply { background = chip(light) }
        topLeft.addView(windArrow, LinearLayout.LayoutParams(button, button))
        offlineGlyph = ImageView(this).apply {
            setImageResource(R.drawable.ic_cloud_off)
            alpha = 0.8f
            contentDescription = getString(R.string.weather_offline_desc)
            visibility = View.GONE
        }
        val g = px(R.dimen.space_m) + px(R.dimen.space_xs)
        topLeft.addView(offlineGlyph, LinearLayout.LayoutParams(g, g).apply { marginStart = px(R.dimen.space_s) })
        chrome.addView(topLeft, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        muteButton = ImageView(this).apply {
            background = chip(light)
            val p = px(R.dimen.space_s) + px(R.dimen.space_xs) / 2
            setPadding(p, p, p, p)
            setOnClickListener { toggleSound() }
        }
        chrome.addView(muteButton, FrameLayout.LayoutParams(button, button, Gravity.TOP or Gravity.END))

        // A severe-weather alert, when there is one: a bold pill between the wind and the sound.
        alertPill = android.widget.TextView(this).apply {
            type(io.github.iamth3doctor.searchlight.ui.Type.BODY)
            typeface = android.graphics.Typeface.create(typeface, 700, false)
            letterSpacing = 0.08f
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            val p = px(R.dimen.space_s)
            setPadding(p * 2, p, p * 2, p)
            setTextColor(if (light) 0xFFFFFFFF.toInt() else color(R.color.on_gold))
            background = GradientDrawable().apply { cornerRadius = 999f; setColor(color(if (light) R.color.accent else R.color.gold)) }
            elevation = px(R.dimen.space_xs).toFloat()
            visibility = View.GONE
            setOnClickListener { showAlerts() }
        }
        chrome.addView(alertPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, button, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            .apply { leftMargin = button + px(R.dimen.space_m) * 2; rightMargin = button + px(R.dimen.space_s) })

        // The hero: the temperature, oversized and dimensional, over the scene; the condition and high/low on glass under it.
        val heroBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        hero = HeroTempView(this)
        heroBox.addView(hero, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val heroText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = px(R.dimen.space_s)
            setPadding(p * 2, p, p * 2, p)
            background = GradientDrawable().apply { cornerRadius = pxF(R.dimen.radius_card) / 1.5f; setColor(glass(light)) }
        }
        heroCondition = android.widget.TextView(this).apply {
            type(io.github.iamth3doctor.searchlight.ui.Type.TILE_TITLE)
            typeface = android.graphics.Typeface.create(typeface, 700, false)
            setTextColor(if (light) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        heroHiLo = android.widget.TextView(this).apply {
            type(io.github.iamth3doctor.searchlight.ui.Type.BODY)
            typeface = android.graphics.Typeface.create(typeface, 600, false)
            setTextColor(if (light) 0xA61C1C21.toInt() else color(R.color.on_surface_dim))
            gravity = Gravity.CENTER
        }
        heroText.addView(heroCondition)
        heroText.addView(heroHiLo)
        heroBox.addView(heroText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = px(R.dimen.space_xs) })
        chrome.addView(heroBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            .apply { topMargin = button + px(R.dimen.space_m) })

        bar = DayBarView(this).apply {
            this.light = light
            background = FrostedDrawable(this, pxF(R.dimen.radius_card) / 1.5f, glass(light), glass(light),
                color(if (light) R.color.settings_glass_border_light else R.color.glass_border), pxF(R.dimen.stroke_glass), wallpaper = false)
            onScrub = { render() }
        }
        chrome.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(R.dimen.size_weather_bar), Gravity.BOTTOM))

        // The numbers: over the scene (blurred behind them), above the bar.
        dataOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setOnClickListener { toggleData() } // outside the panel: back to the scene
        }
        panel = WeatherDataPanel(this, light).apply { clock = { this@WeatherActivity.clock() } }
        val card = FrameLayout(this).apply {
            isClickable = true
            background = FrostedDrawable(this, pxF(R.dimen.radius_card), glass(light), glass(light),
                color(if (light) R.color.settings_glass_border_light else R.color.glass_border), pxF(R.dimen.stroke_glass), wallpaper = false)
            clipToOutline = true
        }
        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            val p = px(R.dimen.space_m)
            setPadding(p, p, p, p)
            clipToPadding = false
            addView(panel.body)
        }
        card.addView(scroll)
        val close = ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (light) 0xA61C1C21.toInt() else color(R.color.on_surface_dim))
            val p = px(R.dimen.space_s)
            setPadding(p, p, p, p)
            contentDescription = getString(R.string.weather_detail_close)
            setOnClickListener { toggleData() }
        }
        card.addView(close, FrameLayout.LayoutParams(button, button, Gravity.TOP or Gravity.END))
        dataOverlay.addView(card, FrameLayout.LayoutParams(
            minOf(px(R.dimen.size_weather_detail_max), resources.displayMetrics.widthPixels - 2 * m),
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER_HORIZONTAL,
        ).apply { topMargin = button + px(R.dimen.space_s); bottomMargin = px(R.dimen.size_weather_bar) + px(R.dimen.space_s) })
        chrome.addView(dataOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // With the numbers open, the bar, the sound, the wind and any alert stay at hand above them.
        bar.bringToFront()
        topLeft.bringToFront()
        muteButton.bringToFront()
        alertPill.bringToFront()
        panel.precip.onScrub = { t -> if (t == null) bar.goLive() else bar.scrubTo(t) }

        // The alert's details: over everything, until closed.
        alertOverlay = FrameLayout(this).apply {
            visibility = View.GONE
            setBackgroundColor(0x66000000)
            setOnClickListener { hideAlerts() }
        }
        val alertCard = FrameLayout(this).apply {
            isClickable = true
            background = FrostedDrawable(this, pxF(R.dimen.radius_card), glass(light), glass(light),
                color(if (light) R.color.settings_glass_border_light else R.color.glass_border), pxF(R.dimen.stroke_glass), wallpaper = false)
            clipToOutline = true
        }
        alertList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        alertCard.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            val p = px(R.dimen.space_m)
            setPadding(p, p, p, p)
            addView(alertList)
        })
        alertCard.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (light) 0xA61C1C21.toInt() else color(R.color.on_surface_dim))
            val p = px(R.dimen.space_s)
            setPadding(p, p, p, p)
            contentDescription = getString(R.string.weather_detail_close)
            setOnClickListener { hideAlerts() }
        }, FrameLayout.LayoutParams(button, button, Gravity.TOP or Gravity.END))
        alertOverlay.addView(alertCard, FrameLayout.LayoutParams(
            minOf(px(R.dimen.size_weather_detail_max), resources.displayMetrics.widthPixels - 2 * m),
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER,
        ).apply { topMargin = button; bottomMargin = button })
        chrome.addView(alertOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        lightTheme = light

        root.addView(chrome, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chrome.padForSystemBars()
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

    private var lightTheme = false

    /** Back: an alert's details close first, then the numbers, then the screen. */
    fun back() {
        when {
            alertShown -> hideAlerts()
            dataShown -> toggleData()
            else -> finish()
        }
    }

    private fun glass(light: Boolean) = color(if (light) R.color.settings_glass_light else R.color.settings_glass_dark)

    private fun chip(light: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(glass(light))
        setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1),
            color(if (light) R.color.settings_glass_border_light else R.color.glass_border))
    }

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
        updateSoundButton()
        if (soundOn()) sound.start()
        render()
    }

    override fun onPause() {
        sound.stop()
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

    @Deprecated("Back hides the numbers first")
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

    fun render() {
        if (!::bar.isInitialized) return
        val state = repo.state
        val now = clock()
        bar.update(state.data, now, bar.scrubTime)
        val c = conditions()
        scene.conditions = c
        windArrow.color = if (isLightTheme()) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt()
        windArrow.set(c.windTowardDeg, c.windMph)
        offlineGlyph.visibility = if (state.data != null && state.problem == WeatherRepository.Problem.NETWORK) View.VISIBLE else View.GONE
        offlineGlyph.setColorFilter(if (isLightTheme()) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt())
        sound.rain = c.rain.toFloat()
        sound.wind = (c.windMph / 30.0).coerceIn(0.0, 1.0).toFloat()

        // The hero follows the scene: now, or the hour being played.
        val data = state.data
        hero.number = if (data != null) io.github.iamth3doctor.searchlight.weather.WeatherText.f(c.tempC).toString() else "--"
        heroCondition.text = if (data != null) io.github.iamth3doctor.searchlight.weather.WeatherCodes.describe(c.code) else getString(R.string.weather_loading)
        heroHiLo.text = if (data != null) getString(R.string.weather_widget_hi_lo,
            "${io.github.iamth3doctor.searchlight.weather.WeatherText.f(data.highC)}°", "${io.github.iamth3doctor.searchlight.weather.WeatherText.f(data.lowC)}°") else ""
        heroHiLo.visibility = if (data != null) View.VISIBLE else View.GONE

        // Alerts: a pill only while one is in effect; nothing at all otherwise.
        val alerts = io.github.iamth3doctor.searchlight.weather.NwsAlerts.active(data?.alerts, now)
        if (alerts.isEmpty()) {
            alertPill.visibility = View.GONE
            if (alertShown) hideAlerts()
        } else {
            alertPill.visibility = View.VISIBLE
            alertPill.text = io.github.iamth3doctor.searchlight.weather.NwsAlerts.pill(alerts.first()) +
                if (alerts.size > 1) "  +${alerts.size - 1}" else ""
            alertPill.contentDescription = getString(R.string.weather_alert_pill_desc, alerts.first().event)
        }

        // The numbers rebuild when the reading changes; scrubbing only moves the precip card along.
        if (dataShown && state !== panelState) {
            panel.render(state)
            panelState = state
        }
        panel.precip.update(data, now, bar.scrubTime)
        scene.contentDescription = getString(R.string.weather_scene_desc, "${io.github.iamth3doctor.searchlight.weather.WeatherText.f(c.tempC)}°F",
            io.github.iamth3doctor.searchlight.weather.WeatherCodes.describe(c.code))
    }

    private var panelState: WeatherRepository.State? = null

    fun toggleData() {
        val show = !dataShown
        if (show) {
            panel.render(repo.state)
            panelState = repo.state
            panel.precip.update(repo.state.data, clock(), bar.scrubTime)
        }
        dataOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scene.setRenderEffect(if (show) RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP) else null)
        }
    }

    // ------------------------------------------------------------------ alerts

    /** The alerts in effect, in full: event, headline, when, what's happening, what to do. */
    fun showAlerts() {
        val data = repo.state.data ?: return
        val alerts = io.github.iamth3doctor.searchlight.weather.NwsAlerts.active(data.alerts, clock())
        if (alerts.isEmpty()) return
        alertList.removeAllViews()
        val fg = if (lightTheme) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt()
        val dim = if (lightTheme) 0xA61C1C21.toInt() else color(R.color.on_surface_dim)
        val accent = color(if (lightTheme) R.color.accent else R.color.gold)
        val zone = java.time.ZoneOffset.ofTotalSeconds(data.detail?.utcOffsetSec ?: 0)
        val fmt = java.time.format.DateTimeFormatter.ofPattern(
            if (android.text.format.DateFormat.is24HourFormat(this)) "EEE HH:mm" else "EEE h:mm a", resources.configuration.locales[0])
        fun at(ms: Long?) = ms?.let { fmt.format(java.time.Instant.ofEpochMilli(it).atOffset(zone)) }
        fun text(style: Int, color: Int, weight: Int = 400, value: CharSequence) = android.widget.TextView(this).apply {
            type(style)
            if (weight != 400) typeface = android.graphics.Typeface.create(typeface, weight, false)
            setTextColor(color)
            text = value
            setLineSpacing(0f, 1.15f)
        }
        fun lp(top: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = top }
        alerts.forEachIndexed { i, a ->
            val top = if (i == 0) 0 else px(R.dimen.space_l)
            alertList.addView(text(io.github.iamth3doctor.searchlight.ui.Type.CAPTION, accent, 700,
                io.github.iamth3doctor.searchlight.weather.NwsAlerts.pill(a)).apply { letterSpacing = 0.08f; tag = "alert_pill" }, lp(top))
            alertList.addView(text(io.github.iamth3doctor.searchlight.ui.Type.TILE_TITLE, fg, 700, a.event).apply { tag = "alert_event" }, lp(px(R.dimen.space_xs)))
            if (a.headline.isNotBlank()) alertList.addView(text(io.github.iamth3doctor.searchlight.ui.Type.BODY, fg, 600, a.headline).apply { tag = "alert_headline" }, lp(px(R.dimen.space_xs)))
            val whenText = listOfNotNull(at(a.effective)?.let { getString(R.string.weather_alert_effective, it) },
                at(a.expires)?.let { getString(R.string.weather_alert_expires, it) }).joinToString("  ·  ")
            if (whenText.isNotBlank()) alertList.addView(text(io.github.iamth3doctor.searchlight.ui.Type.CAPTION, dim, 600, whenText).apply { tag = "alert_times" }, lp(px(R.dimen.space_xs)))
            if (a.description.isNotBlank()) alertList.addView(text(io.github.iamth3doctor.searchlight.ui.Type.BODY, fg, value = a.description).apply { tag = "alert_description" }, lp(px(R.dimen.space_s)))
            if (a.instruction.isNotBlank()) {
                alertList.addView(text(io.github.iamth3doctor.searchlight.ui.Type.CAPTION, accent, 700, getString(R.string.weather_alert_what_to_do).uppercase())
                    .apply { letterSpacing = 0.08f }, lp(px(R.dimen.space_s)))
                alertList.addView(text(io.github.iamth3doctor.searchlight.ui.Type.BODY, fg, value = a.instruction).apply { tag = "alert_instruction" }, lp(px(R.dimen.space_xs)))
            }
            if (a.sender.isNotBlank()) alertList.addView(text(io.github.iamth3doctor.searchlight.ui.Type.CAPTION, dim, value = a.sender), lp(px(R.dimen.space_s)))
        }
        alertOverlay.visibility = View.VISIBLE
    }

    fun hideAlerts() {
        alertOverlay.visibility = View.GONE
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
        muteButton.setColorFilter(if (isLightTheme()) 0xFF1C1C21.toInt() else 0xFFFFFFFF.toInt())
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

        /** Tests: stand in for silent / Do Not Disturb (null: the real state). */
        @Volatile var quietOverride: Boolean? = null

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
