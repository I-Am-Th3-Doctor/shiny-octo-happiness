package io.github.iamth3doctor.searchlight.iconpack

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.core.content.ContextCompat
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.data.Prefs
import java.util.Calendar
import java.util.WeakHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The app icons coming alive on Home while the icon theme is on. Each list that shows app icons
 * hands its icon views to [track]; when Home opens ([homeOpened]) every icon on screen makes its
 * move (its app's own from the theme, else a generic one), whoever drew it, one after another, and while Home stays in view ([startPeriodic]) one or two of
 * them move again about every 30 seconds, never all at once. The moves are only the views' own
 * rotation, lift and scale: they never take a touch, so a tap opens the app at once, mid-move or
 * not. Nothing moves under Battery Saver or with the system's animations off ([paused]).
 * Main thread only.
 */
object IconMotion {

    private val views = WeakHashMap<ImageView, AppEntry>()
    private val running = WeakHashMap<ImageView, ValueAnimator>()
    private val main = Handler(Looper.getMainLooper())
    private var repo: AppRepository? = null
    private var pausedCheck: () -> Boolean = { false }

    /** Tests: true moves whatever the system says, false holds still; null: the system decides. */
    @Volatile var forceMotion: Boolean? = null

    /** How often one or two icons move again while Home is in view. */
    const val PERIOD_MS = 30_000L

    /** Animations held: Battery Saver, or the system's animations removed (Reduce Motion). */
    val paused: Boolean get() = forceMotion?.let { !it } ?: pausedCheck()

    /** Moves made (tests): icon key and move. */
    val played = ArrayList<Pair<String, IconTheme.Move>>()

    internal fun attach(repo: AppRepository, paused: () -> Boolean) {
        this.repo = repo
        pausedCheck = paused
    }

    /** [iv] shows [entry]'s icon now (lists call this as they bind). */
    fun track(iv: ImageView, entry: AppEntry) {
        views[iv] = entry
    }

    /** [iv] no longer shows an app's icon (a list reused it for something else). */
    fun untrack(iv: ImageView) {
        views.remove(iv)
        running.remove(iv)?.cancel()
    }

    /** The app [iv] shows (tests). */
    fun entryOf(iv: ImageView): AppEntry? = views[iv]

    /** Is [iv] moving (tests)? */
    fun moving(iv: ImageView): Boolean = running[iv] != null

    /** The app icons on screen now, with their moves (every one, while the theme is on). */
    fun onScreen(): List<Pair<ImageView, IconTheme.Move>> {
        val r = repo ?: return emptyList()
        val rect = Rect()
        return views.entries.mapNotNull { (iv, entry) ->
            val icon = r.moveFor(entry) ?: return@mapNotNull null
            if (!iv.isAttachedToWindow || !iv.isShown || iv.width == 0 || !iv.getGlobalVisibleRect(rect)) return@mapNotNull null
            iv to icon
        }
    }

    /** Home opened: each app icon on screen makes its move, a moment after the one before. */
    fun homeOpened(): Int {
        if (paused) return 0
        val icons = onScreen()
        icons.forEachIndexed { i, (iv, move) -> androidx.core.os.HandlerCompat.postDelayed(main, { play(iv, move) }, PLAYS, OPEN_DELAY_MS + i * STAGGER_MS) }
        return icons.size
    }

    /** One or two app icons on screen make their move (never all of them). */
    fun nudge(): Int {
        if (paused) return 0
        val icons = onScreen().filter { running[it.first] == null }.shuffled()
        if (icons.isEmpty()) return 0
        val n = if (icons.size >= 4 && Math.random() < 0.5) 2 else 1
        icons.take(n).forEachIndexed { i, (iv, move) -> androidx.core.os.HandlerCompat.postDelayed(main, { play(iv, move) }, PLAYS, i * 450L) }
        return n.coerceAtMost(icons.size)
    }

    private val periodic = object : Runnable {
        override fun run() {
            nudge()
            main.postDelayed(this, PERIOD_MS)
        }
    }

    /** While Home is in view: a move every [PERIOD_MS]. */
    fun startPeriodic() {
        main.removeCallbacks(periodic)
        main.postDelayed(periodic, PERIOD_MS)
    }

    /** Home is out of view: no more moves, and every icon back as it was. */
    fun stopPeriodic() {
        main.removeCallbacks(periodic)
        settle()
    }

    /** Every move stopped and its icon back in place (the fold opened or closed, or Home left). */
    fun settle() {
        main.removeCallbacksAndMessages(PLAYS)
        running.values.toList().forEach { it.cancel() }
        running.clear()
    }

    /**
     * [iv] makes [move], one of the moves of searchlight-icon-actions.md. Only its own rotation,
     * offset, scale and (for a flash) tint change, and go back after, from wherever they were: a
     * tap still lands on it and opens the app straight away.
     */
    fun play(iv: ImageView, move: IconTheme.Move): Boolean {
        if (paused || !iv.isAttachedToWindow) return false
        running.remove(iv)?.cancel()
        views[iv]?.let { played += it.key to move; if (played.size > 100) played.subList(0, 50).clear() }
        val rot = iv.rotation
        val rotY = iv.rotationY
        val ty = iv.translationY
        val tx = iv.translationX
        val filter = iv.colorFilter
        val sx = iv.scaleX
        val sy = iv.scaleY
        val px = iv.pivotX
        val py = iv.pivotY
        val pivotSet = iv.isPivotSet
        fun reset() {
            iv.rotation = rot; iv.rotationY = rotY; iv.translationY = ty; iv.translationX = tx; iv.scaleX = sx; iv.scaleY = sy
            if (move == IconTheme.Move.FLASH) iv.colorFilter = filter
            if (pivotSet) { iv.pivotX = px; iv.pivotY = py } else iv.resetPivot()
        }
        val anim = ValueAnimator.ofFloat(0f, 1f).setDuration(duration(move))
        anim.interpolator = LinearInterpolator()
        anim.addUpdateListener {
            val t = it.animatedValue as Float
            // Measured every frame: folded or unfolded, the icon moves about its own size.
            val w = iv.width.toFloat()
            val h = iv.height.toFloat()
            iv.pivotX = w / 2
            iv.pivotY = h / 2
            fun scale(k: Float) { iv.scaleX = sx * k; iv.scaleY = sy * k }
            when (move) {
                IconTheme.Move.SPIN -> iv.rotation = rot + 360f * ease(t)
                IconTheme.Move.SWING -> { iv.pivotY = 0f; iv.rotation = rot + 16f * sin(3 * PI * t).toFloat() * (1 - t) }
                IconTheme.Move.BOUNCE -> iv.translationY = ty - 0.16f * h * abs(sin(2 * PI * t)).toFloat() * (1 - t * 0.6f)
                IconTheme.Move.FLIP -> {
                    iv.cameraDistance = 12 * w.coerceAtLeast(1f) * iv.resources.displayMetrics.density
                    iv.rotationY = rotY + 360f * ease(t)
                }
                // A record on a turntable: two full turns, speeding up and slowing down.
                IconTheme.Move.RECORD -> iv.rotation = rot + 720f * ease(t)
                // An alarm bell ringing: a fast shake from near the top, swelling and dying away.
                IconTheme.Move.RING -> { iv.pivotY = h * 0.2f; iv.rotation = rot + 12f * sin(16 * PI * t).toFloat() * sin(PI * t).toFloat() }
                // A camera shutter: a quick squeeze down, then a pop back out past its size, and settle.
                IconTheme.Move.SHUTTER -> scale(when {
                    t < 0.3f -> 1 - 0.18f * sin(PI / 2 * t / 0.3f).toFloat()
                    t < 0.65f -> 0.82f + 0.26f * sin(PI / 2 * (t - 0.3f) / 0.35f).toFloat()
                    else -> 1.08f - 0.08f * ease((t - 0.65f) / 0.35f)
                })
                // A quick pop: up past its size and back.
                IconTheme.Move.POP -> scale(1 + 0.18f * sin(PI * t).toFloat() * (1 - 0.35f * t))
                // A loose side-to-side wobble about its middle, dying away.
                IconTheme.Move.WOBBLE -> {
                    iv.rotation = rot + 9f * sin(5 * PI * t).toFloat() * (1 - t)
                    iv.translationX = tx + 0.05f * w * sin(5 * PI * t).toFloat() * (1 - t)
                }
                // Breathing: two slow, gentle swells.
                IconTheme.Move.PULSE -> scale(1 + 0.08f * sin(2 * PI * t).toFloat().let { it * it })
                // Off to one side and back.
                IconTheme.Move.SLIDE -> iv.translationX = tx + 0.22f * w * sin(PI * ease(t)).toFloat()
                // A speech bubble popping up from its corner, with a little second pop.
                IconTheme.Move.CHAT -> {
                    iv.pivotX = 0f; iv.pivotY = h
                    val k = if (t < 0.6f) 0.2f * sin(PI * t / 0.6f).toFloat() else 0.07f * sin(PI * (t - 0.6f) / 0.4f).toFloat()
                    scale(1 + k)
                    iv.rotation = rot - 30f * k
                }
                // A clock's tick-tock: short snaps either side, then still.
                IconTheme.Move.TICK -> {
                    val step = (t * 6).toInt()
                    iv.rotation = rot + if (step >= 5) 0f else if (step % 2 == 0) 7f else -7f
                }
                // A gentle tilt wave from its foot, swelling and dying away.
                IconTheme.Move.WAVE -> {
                    iv.pivotY = h
                    iv.rotation = rot + 8f * sin(3 * PI * t).toFloat() * sin(PI * t).toFloat()
                }
                // A quick flash of light over the icon's own shape.
                IconTheme.Move.FLASH -> {
                    val a = (0.65f * sin(PI * t).toFloat() * 255).toInt().coerceIn(0, 255)
                    iv.colorFilter = if (a == 0) filter else android.graphics.PorterDuffColorFilter(
                        android.graphics.Color.argb(a, 255, 255, 255), android.graphics.PorterDuff.Mode.SRC_ATOP)
                    scale(1 + 0.04f * sin(PI * t).toFloat())
                }
                // Toward the viewer and back.
                IconTheme.Move.ZOOM -> scale(1 + 0.3f * sin(PI * ease(t)).toFloat())
                // A cog turning a notch, with a little settle at the end.
                IconTheme.Move.GEAR -> iv.rotation = rot + 45f * ease(t) + 4f * sin(PI * t).toFloat() * t
                // A pin dropping in and landing with a small bounce.
                IconTheme.Move.DROP -> iv.translationY = ty - h * if (t < 0.55f) 0.3f * (1 - (t / 0.55f) * (t / 0.55f))
                    else 0.07f * sin(PI * (t - 0.55f) / 0.45f).toFloat()
            }
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: Animator) {
                reset()
                if (running[iv] === anim) running.remove(iv)
            }
        })
        running[iv] = anim
        anim.start()
        return true
    }

    /**
     * The live icons on screen drawn again, as time moves on: [which] picks them (the clocks each
     * minute, the calendars at midnight). The old picture stays until the new one is ready.
     */
    fun refresh(which: (IconTheme.Icon) -> Boolean) {
        val r = repo ?: return
        val entries = views.values.filter { r.themedIcon(it)?.let(which) == true }.distinctBy { it.key }
        if (entries.isEmpty()) return
        for (e in entries) r.forgetIcon(e)
        for ((iv, e) in views.entries.toList()) {
            if (entries.none { it.key == e.key }) continue
            r.icon(e) { bmp -> if (views[iv] === e) iv.setImageBitmap(bmp) }?.let { iv.setImageBitmap(it) }
        }
    }

    private fun ease(t: Float) = t * t * (3 - 2 * t)

    private fun duration(m: IconTheme.Move) = when (m) {
        IconTheme.Move.SPIN, IconTheme.Move.FLIP -> 900L
        IconTheme.Move.SWING -> 1100L
        IconTheme.Move.BOUNCE, IconTheme.Move.DROP -> 900L
        IconTheme.Move.RECORD -> 1800L
        IconTheme.Move.RING -> 1000L
        IconTheme.Move.SHUTTER -> 600L
        IconTheme.Move.GEAR -> 1000L
        IconTheme.Move.POP, IconTheme.Move.FLASH -> 550L
        IconTheme.Move.WOBBLE -> 1000L
        IconTheme.Move.PULSE -> 1600L
        IconTheme.Move.SLIDE, IconTheme.Move.ZOOM -> 900L
        IconTheme.Move.CHAT -> 750L
        IconTheme.Move.TICK -> 1200L
        IconTheme.Move.WAVE -> 1400L
    }

    private const val OPEN_DELAY_MS = 350L
    /** The token on the moves waiting their turn. */
    private val PLAYS = Any()
    private const val STAGGER_MS = 90L
}

/**
 * The icon theme switched on and off, and its live icons kept current: the clock every minute
 * (still at 10:10 while animations pause), the calendar when the date turns over.
 */
class IconThemes(private val context: Context, private val prefs: Prefs, private val repo: AppRepository) {

    /** The bundled theme (assets/icontheme). */
    var theme: IconTheme = IconTheme.load(context)
        private set

    /**
     * Tests: use [t] in place of the bundled theme (a temporary icon to prove the mapping), or
     * the bundled theme again with null.
     */
    fun useTheme(t: IconTheme?) {
        theme = t ?: IconTheme.load(context)
        apply()
    }

    private val power = context.getSystemService(PowerManager::class.java)

    /** Tests: stand in for Battery Saver (null: the real state). */
    @Volatile var powerSaveOverride: Boolean? = null

    private fun powerSave() = powerSaveOverride ?: (power?.isPowerSaveMode == true)

    private var lastStill = false
    private var lastDay = -1

    /** The theme on (its icons where it has one) or off (every icon the system's, or the pack's). */
    var enabled: Boolean
        get() = prefs.iconTheme
        set(value) {
            prefs.iconTheme = value
            apply()
        }

    fun start() {
        IconMotion.attach(repo) { powerSave() || !ValueAnimator.areAnimatorsEnabled() }
        repo.iconsStill = { IconMotion.paused }
        apply()
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun apply() {
        if (!prefs.iconTheme) IconMotion.settle()
        lastStill = IconMotion.paused
        lastDay = today()
        repo.iconTheme = if (prefs.iconTheme) theme else null
    }

    /** How many of the installed apps are themed: drawn by the theme (or the applied pack), and the live icons. */
    fun themedCount(): Int = repo.apps.filter { !it.isSelf && repo.isThemed(it) }.distinctBy { it.packageName }.size

    private fun today() = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)

    /** Time moved on (a minute, a new date, a new zone) or Battery Saver changed: the live icons follow. */
    fun timeMoved(dateMayHaveChanged: Boolean) {
        if (repo.iconTheme == null) return
        val still = IconMotion.paused
        if (still && !lastStill) IconMotion.settle()
        lastStill = still
        val day = today()
        val newDay = dateMayHaveChanged || day != lastDay
        lastDay = day
        IconMotion.refresh { it.live == IconTheme.Live.CLOCK || (newDay && it.live == IconTheme.Live.CALENDAR) }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            timeMoved(dateMayHaveChanged = intent.action != Intent.ACTION_TIME_TICK && intent.action != PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
    }
}
