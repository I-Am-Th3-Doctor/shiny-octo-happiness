package io.github.iamth3doctor.searchlight.weatherapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.os.PowerManager
import android.view.Choreographer
import android.view.View
import androidx.core.content.ContextCompat
import io.github.iamth3doctor.searchlight.weather.SceneConditions

/**
 * The living weather scene as a View ([SceneRenderer] does the drawing). It animates only while
 * it can be seen (attached, its window visible, the view shown) and never in battery saver: then
 * it holds still on its last frame. The home screen's widget [mini] runs at 30 fps, the full
 * screen at 30: weather moves slowly, and between frames the UI thread is left idle (good for the
 * battery, and for anything waiting on the app to settle).
 */
class WeatherSceneView(context: Context, val mini: Boolean) : View(context) {

    val renderer = SceneRenderer(resources.displayMetrics.density, mini)

    var conditions: SceneConditions? = null
        set(value) {
            val first = field == null
            field = value
            if (value != null) renderer.setTarget(value, immediate = first)
            updateRunning()
            invalidate()
        }

    /** Snap straight to the next [conditions] (no easing): a forced scene in tests, or a new place. */
    fun jumpTo(c: SceneConditions) {
        conditions = c
        renderer.setTarget(c, immediate = true)
        invalidate()
    }

    /** Whether the scene is animating now (tests). */
    var animating = false
        private set

    private var lastNs = 0L
    private val choreographer = Choreographer.getInstance()
    private val frameMs = if (mini) 1000L / 24 else 1000L / 30

    private val frame = object : Choreographer.FrameCallback {
        override fun doFrame(now: Long) {
            if (!animating) return
            val dt = if (lastNs == 0L) 0.0 else (now - lastNs) / 1e9
            lastNs = now
            renderer.step(dt)
            invalidate()
            // The next frame after a pause, not on every vsync: the thread idles in between.
            val spent = (System.nanoTime() - now) / 1_000_000
            choreographer.postFrameCallbackDelayed(this, (frameMs - spent - 8).coerceAtLeast(1))
        }
    }

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = updateRunning()
    }
    private var receiverOn = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun powerSave(): Boolean = powerSaveOverride
        ?: (context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true)

    /** Starts or stops the animation to match whether it may run now. */
    fun updateRunning() {
        val should = isAttachedToWindow && windowVisibility == VISIBLE && isShown && conditions != null &&
            width > 0 && !powerSave()
        if (should == animating) return
        animating = should
        if (should) {
            lastNs = 0L
            choreographer.postFrameCallback(frame)
        } else {
            choreographer.removeFrameCallback(frame)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!receiverOn) {
            ContextCompat.registerReceiver(context, powerReceiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverOn = true
        }
        updateRunning()
    }

    override fun onDetachedFromWindow() {
        if (receiverOn) {
            runCatching { context.unregisterReceiver(powerReceiver) }
            receiverOn = false
        }
        animating = false
        choreographer.removeFrameCallback(frame)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateRunning()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        renderer.resize(w, h)
        updateRunning()
    }

    override fun onDraw(canvas: Canvas) {
        // Still (battery saver, or not yet running): one settled frame.
        if (!animating && renderer.frames == 0L) renderer.step(0.0)
        renderer.draw(canvas)
    }

    companion object {
        /** Tests: stand in for battery saver (null: the real setting). */
        @Volatile var powerSaveOverride: Boolean? = null
    }
}
