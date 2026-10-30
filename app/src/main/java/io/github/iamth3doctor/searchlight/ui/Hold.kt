package io.github.iamth3doctor.searchlight.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs

/**
 * A long-press with a shorter threshold than the system's (~500ms): [onHold] runs once the
 * finger has stayed down, without wandering, for [delayMs]. Lifting or moving off sooner
 * cancels it, so a quick tap is still just a tap (the view's click listener runs as usual).
 * Once it has fired, the rest of that touch is swallowed: no click follows.
 *
 * Also registered as the view's long-click action, so TalkBack (and performLongClick) still
 * open it.
 *
 * After it fires, the same finger's moves go to [onHeldMove] and its lift (or a cancel) to
 * [onHeldEnd], both with screen coordinates: what lets you slide on into what the hold opened.
 */
@SuppressLint("ClickableViewAccessibility") // clicks still go through the view's own handling
fun View.setOnHoldListener(
    delayMs: Long,
    onHold: (View) -> Unit,
    onHeldMove: ((rawX: Float, rawY: Float) -> Unit)? = null,
    onHeldEnd: ((rawX: Float, rawY: Float, cancelled: Boolean) -> Unit)? = null,
) {
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var fired = false
    val fire = Runnable {
        fired = true
        isPressed = false
        cancelLongPress() // the system's own long-press must not fire it a second time
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onHold(this)
    }
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fired = false
                downX = e.x
                downY = e.y
                v.postDelayed(fire, delayMs)
            }
            MotionEvent.ACTION_MOVE ->
                if (fired) onHeldMove?.invoke(e.rawX, e.rawY)
                else if (abs(e.x - downX) > slop || abs(e.y - downY) > slop) v.removeCallbacks(fire)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(fire)
                if (fired) onHeldEnd?.invoke(e.rawX, e.rawY, e.actionMasked == MotionEvent.ACTION_CANCEL)
            }
        }
        fired
    }
    setOnLongClickListener {
        onHold(it)
        true
    }
}
