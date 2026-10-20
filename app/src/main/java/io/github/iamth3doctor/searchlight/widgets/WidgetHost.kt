package io.github.iamth3doctor.searchlight.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs

/** Our AppWidgetHost: same as the platform one, but hands out long-pressable host views. */
class SearchlightWidgetHost(context: Context, hostId: Int) : AppWidgetHost(context, hostId) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        LongPressWidgetHostView(context)
}

/**
 * A widget's own buttons swallow touches, so a normal long-click listener would never fire on
 * most widgets. This watches every touch that passes through (like the stock launcher does):
 * if the finger stays put for the long-press timeout, it takes over the gesture, cancels it for
 * the widget, and reports a long press. Scrolling the page cancels it.
 */
class LongPressWidgetHostView(context: Context) : AppWidgetHostView(context) {

    var onLongPress: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var fired = false

    private val check = Runnable {
        fired = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke()
    }

    init {
        // Swallow taps on the widget's empty areas, so the page underneath doesn't treat a
        // press on the widget as a press on empty space.
        isClickable = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = ev.x
                downY = ev.y
                postDelayed(check, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE ->
                if (abs(ev.x - downX) > slop || abs(ev.y - downY) > slop) removeCallbacks(check)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> removeCallbacks(check)
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = fired || super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(event: MotionEvent): Boolean = fired || super.onTouchEvent(event)
}
