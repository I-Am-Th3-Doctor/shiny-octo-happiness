package io.github.iamth3doctor.searchlight.weatherapp

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ScrollView
import kotlin.math.abs

/**
 * A frame that turns a vertical swipe across it into [onSwipeUp] / [onSwipeDown], while taps and
 * horizontal drags still reach what's inside (a child can keep a drag with
 * requestDisallowInterceptTouchEvent). [canSwipeDown] / [canSwipeUp] say whether a swipe may start
 * now: a list inside only gives way to "swipe down to close" when it's scrolled to the top.
 *
 * With [onDragX], a sideways drag across it (one that starts where [canDragXAt] allows) is its own
 * too: [onDragX] with how far it has moved, then [onDragXEnd] when it lets go.
 */
class SwipeFrame(context: Context) : FrameLayout(context) {

    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var canSwipeUp: () -> Boolean = { true }
    var canSwipeDown: () -> Boolean = { true }

    var onDragX: ((Float) -> Unit)? = null
    var onDragXEnd: (() -> Unit)? = null
    var canDragXAt: (Float, Float) -> Boolean = { _, _ -> true }

    /** Whether a sideways drag is under way. */
    var draggingX = false
        private set
    private var dragXAllowed = false

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minSwipe = 56 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    private fun wantsSwipe(e: MotionEvent): Boolean {
        val dx = e.x - downX
        val dy = e.y - downY
        if (abs(dy) < slop * 1.5f || abs(dy) < abs(dx) * 1.4f) return false
        return if (dy < 0) onSwipeUp != null && canSwipeUp() else onSwipeDown != null && canSwipeDown()
    }

    private fun wantsDragX(e: MotionEvent): Boolean {
        val dx = e.x - downX
        return dragXAllowed && abs(dx) > slop && abs(dx) > abs(e.y - downY) * 1.4f
    }

    private fun down(e: MotionEvent) {
        downX = e.x; downY = e.y; swiping = false; draggingX = false
        dragXAllowed = onDragX != null && canDragXAt(e.x, e.y)
    }

    /** Starts a swipe or a sideways drag if the move is one. */
    private fun claim(e: MotionEvent) {
        if (swiping || draggingX) return
        if (wantsDragX(e)) {
            draggingX = true
            parent?.requestDisallowInterceptTouchEvent(true)
        } else if (wantsSwipe(e)) swiping = true
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> down(e)
            MotionEvent.ACTION_MOVE -> claim(e)
        }
        return swiping || draggingX
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down(e); return true }
            MotionEvent.ACTION_MOVE -> {
                claim(e)
                if (draggingX) onDragX?.invoke(e.x - downX)
            }
            MotionEvent.ACTION_UP -> {
                val dy = e.y - downY
                if (swiping && abs(dy) >= minSwipe) {
                    if (dy < 0) onSwipeUp?.invoke() else onSwipeDown?.invoke()
                }
                swiping = false
                endDragX()
            }
            MotionEvent.ACTION_CANCEL -> { swiping = false; endDragX() }
        }
        return true
    }

    private fun endDragX() {
        if (!draggingX) return
        draggingX = false
        onDragXEnd?.invoke()
    }
}

/**
 * The weather screen's screens, one at a time: the scene ([home]) and, stacked over it, the 7-day
 * view, the details, an alert, the locations. Never two at once: going to a screen, the one showing
 * slides and fades out completely first, then the next slides in; back is the same in reverse.
 */
class ScreenStack(private val home: View, private val onChange: (View) -> Unit) {

    private val stack = ArrayList<View>()
    private var running: Pair<View, View>? = null

    /** The screen showing (or coming in). */
    val top: View get() = stack.lastOrNull() ?: home

    val depth: Int get() = stack.size

    /** Whether a change of screen is under way (tests). */
    val moving: Boolean get() = running != null

    fun contains(v: View) = v in stack

    fun push(screen: View) {
        if (top === screen) return
        val from = top
        stack.remove(screen)
        stack += screen
        swap(from, screen, forward = true)
    }

    fun pop(): Boolean {
        if (stack.isEmpty()) return false
        val from = stack.removeAt(stack.size - 1)
        swap(from, top, forward = false)
        return true
    }

    /** Back to the scene, straight away. */
    fun clear() {
        settle()
        stack.forEach { it.visibility = View.GONE }
        stack.clear()
        show(home)
        onChange(home)
    }

    /** Ends any change under way where it was headed. */
    fun settle() {
        val (from, to) = running ?: return
        from.animate().cancel()
        to.animate().cancel()
        from.visibility = View.GONE
        reset(from)
        show(to)
        running = null
        onChange(to)
    }

    private fun reset(v: View) {
        v.alpha = 1f
        v.translationY = 0f
    }

    private fun show(v: View) {
        reset(v)
        v.visibility = View.VISIBLE
    }

    private fun swap(from: View, to: View, forward: Boolean) {
        settle()
        val d = 40 * from.resources.displayMetrics.density
        running = from to to
        to.visibility = View.GONE
        from.animate().alpha(0f).translationY(if (forward) -d else d).setDuration(OUT_MS).withEndAction {
            from.visibility = View.GONE
            reset(from)
            // Only once the old screen is gone does the new one come in.
            to.alpha = 0f
            to.translationY = if (forward) d else -d
            to.visibility = View.VISIBLE
            onChange(to)
            to.animate().alpha(1f).translationY(0f).setDuration(IN_MS).withEndAction {
                if (running?.second === to) running = null
            }.start()
        }.start()
    }

    private companion object {
        const val OUT_MS = 140L
        const val IN_MS = 200L
    }
}

/**
 * A scroll view that, scrolled to the top, leaves a downward drag to its parent (a [SwipeFrame]'s
 * "swipe down to go back") instead of claiming it for an overscroll.
 */
class TopScrollView(context: Context) : ScrollView(context) {
    private var downY = 0f

    private fun pullingDownAtTop(e: MotionEvent) = !canScrollVertically(-1) && e.y - downY > 0

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) downY = e.y
        if (e.actionMasked == MotionEvent.ACTION_MOVE && pullingDownAtTop(e)) return false
        return super.onInterceptTouchEvent(e)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) downY = e.y
        if (e.actionMasked == MotionEvent.ACTION_MOVE && pullingDownAtTop(e)) return true
        return super.onTouchEvent(e)
    }
}
