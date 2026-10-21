package io.github.iamth3doctor.searchlight.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Swipe
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.pxF
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The invisible widget grid above the home card. Children are widget views, each placed by a
 * [GridSpec] in cells; the view turns cells into pixels at layout time, so nothing pixel-based
 * is ever stored.
 *
 * Edit mode (entered by long-press): the grid becomes visible, widgets get a remove badge and,
 * if resizable, a resize handle. Drag a widget to move it, drag the handle to change its span.
 * A drop that would overlap another widget or leave the grid snaps back. Long-press again, or
 * tap empty space, to leave edit mode.
 */
class WidgetGridView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : ViewGroup(context, attrs) {

    interface Listener {
        fun onEditModeChanged(editing: Boolean)
        fun onCommit(spec: GridSpec)
        fun onRemove(id: Int)
        fun onGridSizeChanged(cols: Int, rows: Int)
        /** Horizontal and vertical resizability. */
        fun resizable(id: Int): Pair<Boolean, Boolean>
        /** Minimum (colSpan, rowSpan) the widget accepts at the current cell size. */
        fun minSpan(id: Int): Pair<Int, Int>
    }

    var listener: Listener? = null

    /** A swipe up on empty space (outside edit mode): opens the app table. */
    var onSwipeUp: (() -> Unit)? = null
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swiped = false
    private val swipeDistance = context.pxF(R.dimen.size_swipe)

    var cols = 4
        private set
    var rows = 6
        private set

    /**
     * While the keyboard is up the area shrinks; the grid keeps the size it had without the
     * keyboard (bottom rows are just covered) instead of reflowing every widget.
     */
    var imeVisible = false

    /** Unfolded: 7 columns (see WidgetGrid.gridSize). */
    var unfolded = false
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** The posture the current [cols] × [rows] were measured for; null before the first measure. */
    var measuredUnfolded: Boolean? = null
        private set

    private val density = resources.displayMetrics.density
    /**
     * Inset of a widget inside its cells. With the grid's own space_s margin, widget edges line up
     * with the home card (space_m from the screen edge) and neighbours sit space_m apart.
     */
    private val gap = context.pxF(R.dimen.space_s)
    private var gridHeight = 0
    private var cellW = 0f
    private var cellH = 0f

    val cellWidthDp get() = cellW / density
    val cellHeightDp get() = cellH / density

    var editMode = false
        private set

    private val specs = HashMap<View, GridSpec>()

    /** The layout currently on screen (for collision checks and first-fit placement). */
    fun placedSpecs(): List<GridSpec> = (0 until childCount).mapNotNull { specs[getChildAt(it)] }

    init {
        setWillNotDraw(false)
        isLongClickable = true
        setOnLongClickListener {
            setEditMode(true)
            enteredThisGesture = true
            true
        }
    }

    fun setWidgets(widgets: List<Pair<View, GridSpec>>) {
        removeAllViews()
        specs.clear()
        for ((v, s) in widgets) {
            (v.parent as? ViewGroup)?.removeView(v)
            v.translationX = 0f
            v.translationY = 0f
            specs[v] = s
            addView(v)
        }
        requestLayout()
        invalidate()
    }

    /** A widget was long-pressed: enter edit mode and let that same finger carry on into a drag. */
    fun enterEditModeFromLongPress() {
        setEditMode(true)
        enteredThisGesture = true
    }

    fun setEditMode(on: Boolean) {
        if (editMode == on) return
        editMode = on
        cancelGesture()
        if (on) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onEditModeChanged(on)
        invalidate()
    }

    // ------------------------------------------------------------------ layout

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        if (!imeVisible || gridHeight == 0 || w != measuredWidth) gridHeight = h
        val (c, r) = WidgetGrid.gridSize(w / density, gridHeight / density, unfolded)
        if (c != cols || r != rows || measuredUnfolded != unfolded) {
            cols = c
            rows = r
            measuredUnfolded = unfolded
            // Re-fit happens outside of measure.
            post { listener?.onGridSizeChanged(cols, rows) }
        }
        cellW = w.toFloat() / cols
        cellH = gridHeight.toFloat() / rows
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val s = specs[v] ?: continue
            v.measure(
                MeasureSpec.makeMeasureSpec((s.colSpan * cellW - 2 * gap).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((s.rowSpan * cellH - 2 * gap).toInt().coerceAtLeast(0), MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val s = specs[v] ?: continue
            val left = (s.col * cellW + gap).toInt()
            val top = (s.row * cellH + gap).toInt()
            v.layout(left, top, left + v.measuredWidth, top + v.measuredHeight)
        }
    }

    private fun cellRect(s: GridSpec) =
        RectF(s.col * cellW + gap, s.row * cellH + gap, (s.col + s.colSpan) * cellW - gap, (s.row + s.rowSpan) * cellH - gap)

    // ------------------------------------------------------------------ drawing

    private val hairline = context.pxF(R.dimen.stroke_hairline)
    private val dash = context.pxF(R.dimen.stroke_dash)
    private val tileRadius = context.pxF(R.dimen.radius_tile)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = hairline
        color = context.color(R.color.outline)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(dash, dash), 0f)
    }
    private val okPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.accent_soft) }
    private val badPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.danger_soft) }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.color(R.color.surface_raised) }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = hairline; color = context.color(R.color.on_surface)
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = hairline; color = context.color(R.color.on_surface); strokeCap = Paint.Cap.ROUND
    }
    private val badgeR = context.pxF(R.dimen.size_badge) / 2

    override fun onDraw(canvas: Canvas) {
        if (!editMode) return
        for (r in 0 until rows) for (c in 0 until cols) {
            canvas.drawRoundRect(RectF(c * cellW + gap, r * cellH + gap, (c + 1) * cellW - gap, (r + 1) * cellH - gap), tileRadius, tileRadius, gridPaint)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        preview?.let { canvas.drawRoundRect(cellRect(it), tileRadius, tileRadius, if (previewOk) okPaint else badPaint) }
        super.dispatchDraw(canvas)
        if (!editMode) return
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val s = specs[v] ?: continue
            val x = v.left + v.translationX
            val y = v.top + v.translationY
            // Remove badge, top-left.
            val bx = x + badgeR * 0.6f
            val by = y + badgeR * 0.6f
            canvas.drawCircle(bx, by, badgeR, badgePaint)
            canvas.drawCircle(bx, by, badgeR, badgeRing)
            val k = badgeR * 0.4f
            canvas.drawLine(bx - k, by - k, bx + k, by + k, glyph)
            canvas.drawLine(bx + k, by - k, bx - k, by + k, glyph)
            // Resize handle, bottom-right, only when the widget can be resized.
            val (rh, rv) = listener?.resizable(s.id) ?: (false to false)
            if (rh || rv) {
                val hx = x + v.width - badgeR * 0.6f
                val hy = y + v.height - badgeR * 0.6f
                canvas.drawCircle(hx, hy, badgeR, badgePaint)
                canvas.drawCircle(hx, hy, badgeR, badgeRing)
                canvas.drawLine(hx - k, hy + k, hx + k, hy - k, glyph)
                canvas.drawLine(hx, hy + k, hx + k, hy, glyph)
            }
        }
    }

    // ------------------------------------------------------------------ edit gestures

    private enum class Gesture { NONE, DRAG, RESIZE, REMOVE, EMPTY }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var gesture = Gesture.NONE
    private var target: View? = null
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var preview: GridSpec? = null
    private var previewOk = false
    /** The long-press that opened edit mode must not also close it when the finger lifts. */
    private var enteredThisGesture = false

    private val exitOnLongPress = Runnable {
        if (gesture != Gesture.NONE && !moved) {
            cancelGesture()
            setEditMode(false)
        }
    }

    /** In edit mode every touch is ours: widgets mustn't react while you're arranging them. */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) return false
        if (ev.actionMasked != MotionEvent.ACTION_DOWN && gesture == Gesture.NONE) {
            // Edit mode just started from a long-press on a widget: that same finger drags it.
            begin(ev.x, ev.y, fromLongPress = true)
        }
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!editMode) {
            detectSwipeUp(ev)
            return super.onTouchEvent(ev)
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                enteredThisGesture = false
                begin(ev.x, ev.y, fromLongPress = false)
            }
            MotionEvent.ACTION_MOVE -> {
                if (gesture == Gesture.NONE) begin(ev.x, ev.y, fromLongPress = true)
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                    moved = true
                    removeCallbacks(exitOnLongPress)
                }
                if (moved) when (gesture) {
                    Gesture.DRAG -> drag(dx, dy)
                    Gesture.RESIZE -> resize(dx, dy)
                    else -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(exitOnLongPress)
                finish()
            }
            MotionEvent.ACTION_CANCEL -> cancelGesture()
        }
        return true
    }

    /** Empty space only: a touch that starts on a widget is the widget's. Long-press still enters edit mode. */
    private fun detectSwipeUp(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swiped = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                // A finger that's travelling isn't long-pressing.
                if (abs(dx) > slop || abs(dy) > slop) cancelLongPress()
                if (!swiped && Swipe.vertical(dx, dy, swipeDistance) == Swipe.Direction.UP) {
                    swiped = true
                    onSwipeUp?.invoke()
                }
            }
        }
    }

    private fun begin(x: Float, y: Float, fromLongPress: Boolean) {
        downX = x
        downY = y
        moved = false
        preview = null
        target = childAt(x, y)
        val v = target
        gesture = when {
            v == null -> Gesture.EMPTY
            !fromLongPress && near(x, y, v.left + badgeR * 0.6f, v.top + badgeR * 0.6f) -> Gesture.REMOVE
            !fromLongPress && isResizable(v) && near(x, y, v.right - badgeR * 0.6f, v.bottom - badgeR * 0.6f) -> Gesture.RESIZE
            else -> Gesture.DRAG
        }
        if (!fromLongPress) postDelayed(exitOnLongPress, ViewConfiguration.getLongPressTimeout().toLong())
        v?.bringToFront()
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float) = abs(x - cx) < badgeR * 2 && abs(y - cy) < badgeR * 2

    private fun isResizable(v: View): Boolean {
        val s = specs[v] ?: return false
        val (h, vert) = listener?.resizable(s.id) ?: return false
        return h || vert
    }

    private fun childAt(x: Float, y: Float): View? {
        for (i in childCount - 1 downTo 0) {
            val v = getChildAt(i)
            if (x >= v.left && x < v.right && y >= v.top && y < v.bottom) return v
        }
        return null
    }

    private fun others(v: View) = specs.filterKeys { it !== v }.values.toList()

    private fun drag(dx: Float, dy: Float) {
        val v = target ?: return
        val s = specs[v] ?: return
        // Keep the widget inside the grid area (it can never slide under the home card).
        val maxY = minOf(height, gridHeight).toFloat()
        v.translationX = dx.coerceIn(-v.left.toFloat(), (width - v.right).toFloat())
        v.translationY = dy.coerceIn(-v.top.toFloat(), maxY - v.bottom)
        val col = ((v.left + v.translationX - gap) / cellW).roundToInt().coerceIn(0, cols - s.colSpan)
        val row = ((v.top + v.translationY - gap) / cellH).roundToInt().coerceIn(0, rows - s.rowSpan)
        showPreview(s.copy(col = col, row = row), v)
    }

    private fun resize(dx: Float, dy: Float) {
        val v = target ?: return
        val s = specs[v] ?: return
        val (canH, canV) = listener?.resizable(s.id) ?: return
        val (minW, minH) = listener?.minSpan(s.id) ?: (1 to 1)
        val w = if (canH) ((v.width + 2 * gap + dx) / cellW).roundToInt().coerceIn(minW.coerceAtMost(cols), cols - s.col) else s.colSpan
        val h = if (canV) ((v.height + 2 * gap + dy) / cellH).roundToInt().coerceIn(minH.coerceAtMost(rows), rows - s.row) else s.rowSpan
        showPreview(s.copy(colSpan = w.coerceAtLeast(1), rowSpan = h.coerceAtLeast(1)), v)
    }

    private fun showPreview(p: GridSpec, v: View) {
        preview = p
        previewOk = WidgetGrid.fits(p, others(v), cols, rows)
        invalidate()
    }

    private fun finish() {
        val v = target
        val p = preview
        when {
            (gesture == Gesture.DRAG || gesture == Gesture.RESIZE) && moved && v != null && p != null && previewOk -> {
                specs[v] = p
                v.translationX = 0f
                v.translationY = 0f
                requestLayout()
                listener?.onCommit(p)
            }
            // Lifting the finger that just opened edit mode doesn't count as a tap.
            enteredThisGesture -> snapBack()
            gesture == Gesture.REMOVE && !moved && v != null -> specs[v]?.let { listener?.onRemove(it.id) }
            gesture == Gesture.EMPTY && !moved -> setEditMode(false)
            else -> snapBack()
        }
        enteredThisGesture = false
        gesture = Gesture.NONE
        target = null
        preview = null
        invalidate()
    }

    /** A drop that would overlap or leave the grid: animate back to where it was. */
    private fun snapBack() {
        target?.animate()?.translationX(0f)?.translationY(0f)?.setDuration(160)?.start()
    }

    private fun cancelGesture() {
        removeCallbacks(exitOnLongPress)
        snapBack()
        gesture = Gesture.NONE
        target = null
        preview = null
        invalidate()
    }
}
