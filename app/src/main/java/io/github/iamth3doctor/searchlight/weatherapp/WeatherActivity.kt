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
        chrome.addView(dataOverlay, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        root.addView(chrome, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        chrome.padForSystemBars()
        setContentView(root)
        current = this
        updateSoundButton()
        if (Build.VERSION.SDK_INT >= 33) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
    }

    /** Back: the numbers close first, then the screen. */
    fun back() {
        if (dataShown) toggleData() else finish()
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
        if (dataShown) panel.render(state)
        scene.contentDescription = getString(R.string.weather_scene_desc, "${io.github.iamth3doctor.searchlight.weather.WeatherText.f(c.tempC)}°F",
            io.github.iamth3doctor.searchlight.weather.WeatherCodes.describe(c.code))
    }

    fun toggleData() {
        val show = !dataShown
        if (show) panel.render(repo.state)
        dataOverlay.visibility = if (show) View.VISIBLE else View.GONE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scene.setRenderEffect(if (show) RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP) else null)
        }
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

        fun open(context: Context) {
            context.startActivity(Intent(context, WeatherActivity::class.java).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }
}
