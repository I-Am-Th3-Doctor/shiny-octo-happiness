package io.github.iamth3doctor.searchlight.widgets

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.calendar.CalendarEvents
import io.github.iamth3doctor.searchlight.calendar.CalendarModel
import io.github.iamth3doctor.searchlight.calendar.DayEvent
import io.github.iamth3doctor.searchlight.calendar.StickyNote
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Not part of Searchlight: kept for the second launcher, and not offered or placed here (a note
 * placed before is taken off the grid; see BuiltInWidget.migrate).
 *
 * The Sticky note widget (2 × 2, not resizable): a paper note stuck to the home screen, tilted a
 * little as if placed by hand, with tape across the top, a soft shadow and a curled corner.
 * Today's date in marker, today's events in pen (see [StickyNote]): the next one in red (blue on
 * pink) and underlined, finished ones checked off and struck through, "+N more" past three, and
 * "Nothing on today" with a little sun when the day is clear. Same events as the Calendar widget.
 *
 * Tap an event line: that event in the calendar app ([onOpenEvent]). Tap anywhere else: the
 * calendar app at today ([onOpenToday]). Long press: the grid's edit mode.
 */
@SuppressLint("ViewConstructor")
class StickyNoteWidgetView(
    context: Context,
    private val events: CalendarEvents,
    /** Degrees, a small tilt either way (from the widget's id, so each note keeps its own). */
    private val tiltDeg: Float,
) : View(context) {

    var onLongPress: (() -> Unit)? = null
    var onOpenToday: ((Long) -> Unit)? = null
    var onOpenEvent: ((DayEvent) -> Unit)? = null
    var onRequestPermission: (() -> Unit)? = null

    /** Now, in ms (tests may pin it). */
    var clock: () -> Long = System::currentTimeMillis

    var paper: StickyNote.Paper = StickyNote.Paper.DEFAULT
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val locale: Locale get() = resources.configuration.locales[0]
    private val density = resources.displayMetrics.density

    /** Today's events as last loaded, and the day they were loaded for. */
    private var loaded: List<DayEvent> = emptyList()
    var loadedDay: LocalDate? = null
        private set

    /** What the note says right now (tests read it). */
    val note: StickyNote.Note
        get() = StickyNote.note(loaded.takeIf { loadedDay == today() }.orEmpty(), clock(), zone, locale) { ms ->
            DateFormat.getTimeFormat(context).format(ms)
        }

    /** No calendar access: the note asks for it. */
    val needsPermission: Boolean get() = !events.hasPermission()

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()

    // ------------------------------------------------------------------ data

    /** Loads today's events (the day may have moved on). */
    fun refresh() {
        val day = today()
        val (from, to) = CalendarModel.queryRange(day, day, zone)
        events.load(from, to) { list ->
            loaded = list
            loadedDay = day
            describe()
            invalidate()
        }
    }

    /** A minute went by: a new day reloads; otherwise what's next and what's done move on. */
    fun tick() {
        if (loadedDay != today()) refresh() else {
            describe()
            invalidate()
        }
    }

    /** Midnight: the note rolls to the new day even if no broadcast comes. */
    private val atMidnight = object : Runnable {
        override fun run() {
            refresh()
            scheduleMidnight()
        }
    }

    private fun scheduleMidnight() {
        removeCallbacks(atMidnight)
        val now = clock()
        val next = today().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        postDelayed(atMidnight, (next - now).coerceIn(1_000L, 24 * 3_600_000L) + 1_000L)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleMidnight()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(atMidnight)
        super.onDetachedFromWindow()
    }

    private fun describe() {
        val n = note
        contentDescription = when {
            needsPermission -> context.getString(R.string.sticky_allow)
            n.empty -> "${n.dateLabel}. ${context.getString(R.string.sticky_nothing)}"
            else -> (listOf(n.dateLabel) + n.lines.map { lineText(it) } +
                listOfNotNull(n.more.takeIf { it > 0 }?.let { context.getString(R.string.sticky_more, it) })).joinToString(". ")
        }
    }

    fun lineText(line: StickyNote.Line) =
        StickyNote.lineText(line, context.getString(R.string.sticky_all_day), context.getString(R.string.sticky_untitled))

    // ------------------------------------------------------------------ touch

    private var downX = 0f
    private var downY = 0f

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
        setOnClickListener { tapAt(downX, downY) }
        setOnLongClickListener {
            onLongPress?.invoke()
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            downX = event.x
            downY = event.y
        }
        return super.onTouchEvent(event)
    }

    /** A tap at ([x], [y]) in this view: an event line opens that event, anywhere else today. */
    fun tapAt(x: Float, y: Float) {
        if (needsPermission) {
            onRequestPermission?.invoke()
            return
        }
        val e = eventAt(x, y)
        if (e != null) onOpenEvent?.invoke(e) else onOpenToday?.invoke(clock())
    }

    /** The event whose line is under ([x], [y]) in this view, if any (the note is tilted: undo that first). */
    fun eventAt(x: Float, y: Float): DayEvent? {
        val p = floatArrayOf(x, y)
        Matrix().also { toPage.invert(it) }.mapPoints(p)
        return lineHits.firstOrNull { it.first.contains(p[0], p[1]) }?.second
    }

    /** Where an event's line is, in this view's coordinates (the tilt applied): its middle (tests tap there). */
    fun lineCenter(index: Int): Pair<Float, Float>? {
        val r = lineHits.getOrNull(index)?.first ?: return null
        val p = floatArrayOf(r.centerX(), r.centerY())
        toPage.mapPoints(p)
        return p[0] to p[1]
    }

    /** The point a share ([nx], [ny]) of the way across and down the paper lands on, in this view (as last drawn). */
    fun pagePoint(nx: Float, ny: Float): Pair<Float, Float> {
        val p = floatArrayOf(sheet.width() * nx, sheet.height() * ny)
        toPage.mapPoints(p)
        return p[0] to p[1]
    }

    /** How far the note leans, in degrees. */
    val tilt: Float get() = tiltDeg

    /** Each written event line's box on the note (unrotated note coordinates), with its event. */
    private val lineHits = ArrayList<Pair<RectF, DayEvent>>()

    // ------------------------------------------------------------------ drawing

    private val marker: Typeface = resources.getFont(R.font.caveat_bold)
    private val pen: Typeface = resources.getFont(R.font.caveat_medium)

    /** The note's own coordinates (0,0 its top left) to this view's: centred, then tilted. */
    private val toPage = Matrix()
    private val sheet = RectF()

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paperPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val texturePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val curlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val texture: Bitmap by lazy { paperTexture() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), getDefaultSize(suggestedMinimumHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        // A square note, with room round it for the shadow and the tilt.
        val side = min(w, h) * 0.86f
        sheet.set(0f, 0f, side, side)
        toPage.reset()
        toPage.postTranslate((w - side) / 2, (h - side) / 2)
        toPage.postRotate(tiltDeg, w / 2, h / 2)

        canvas.save()
        canvas.concat(toPage)
        drawPaper(canvas, side)
        drawWriting(canvas, side)
        drawTape(canvas, side)
        canvas.restore()
    }

    /** The paper's outline: square, the bottom-right corner folded up. */
    private fun outline(side: Float, out: Path) {
        val c = side * CURL
        out.reset()
        out.moveTo(0f, 0f)
        out.lineTo(side, 0f)
        out.lineTo(side, side - c)
        out.quadTo(side - c * 0.18f, side - c * 0.18f, side - c, side)
        out.lineTo(0f, side)
        out.close()
    }

    private fun drawPaper(canvas: Canvas, side: Float) {
        outline(side, path)
        // A soft shadow, a little below: the note sits just off the wall.
        shadowPaint.color = Color.argb(1, 0, 0, 0)
        shadowPaint.setShadowLayer(side * 0.055f, side * 0.01f, side * 0.03f, Color.argb(110, 0, 0, 0))
        canvas.drawPath(path, shadowPaint)

        paperPaint.shader = LinearGradient(0f, 0f, side * 0.3f, side, paper.top, paper.bottom, Shader.TileMode.CLAMP)
        canvas.drawPath(path, paperPaint)
        // Paper fibre: faint speckle over the whole sheet.
        texturePaint.shader = BitmapShader(texture, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        canvas.drawPath(path, texturePaint)

        // The curl: the corner's underside, folded up, with its own little shadow on the paper.
        val c = side * CURL
        val flap = Path().apply {
            moveTo(side, side - c)
            quadTo(side - c * 0.18f, side - c * 0.18f, side - c, side)
            quadTo(side - c * 0.62f, side - c * 0.86f, side - c * 0.08f, side - c * 1.02f)
            close()
        }
        shadowPaint.setShadowLayer(side * 0.02f, -side * 0.006f, -side * 0.006f, Color.argb(70, 0, 0, 0))
        canvas.drawPath(flap, shadowPaint)
        curlPaint.shader = LinearGradient(side - c, side, side - c * 0.4f, side - c * 0.6f,
            paper.curl, paper.top, Shader.TileMode.CLAMP)
        canvas.drawPath(flap, curlPaint)
    }

    private fun drawTape(canvas: Canvas, side: Float) {
        val tw = side * 0.42f
        val th = side * 0.12f
        canvas.save()
        canvas.rotate(-3f - tiltDeg * 0.6f, side / 2, 0f)
        canvas.translate(side / 2 - tw / 2, -th * 0.45f)
        // Torn ends: a little zigzag each side.
        path.reset()
        path.moveTo(0f, 0f)
        path.lineTo(tw, 0f)
        val teeth = 5
        for (i in 1..teeth) path.lineTo(tw + if (i % 2 == 1) th * 0.06f else -th * 0.04f, th * i / teeth)
        path.lineTo(0f, th)
        for (i in teeth - 1 downTo 0) path.lineTo(if (i % 2 == 1) -th * 0.06f else th * 0.04f, th * i / teeth)
        path.close()
        tapePaint.shader = null
        tapePaint.color = Color.argb(150, 246, 242, 228)
        canvas.drawPath(path, tapePaint)
        tapePaint.color = Color.argb(40, 120, 110, 90)
        tapePaint.style = Paint.Style.STROKE
        tapePaint.strokeWidth = density * 0.7f
        canvas.drawPath(path, tapePaint)
        tapePaint.style = Paint.Style.FILL
        canvas.restore()
    }

    private fun drawWriting(canvas: Canvas, side: Float) {
        lineHits.clear()
        val n = note
        val left = side * 0.1f
        val right = side * 0.92f
        val width = right - left

        // The date, in thick marker, as large as fits.
        textPaint.typeface = marker
        textPaint.color = paper.ink
        textPaint.alpha = 255
        textPaint.textSize = side * 0.19f
        val dw = textPaint.measureText(n.dateLabel)
        if (dw > width) textPaint.textSize *= width / dw
        canvas.drawText(n.dateLabel, left, side * 0.3f, textPaint)

        textPaint.typeface = pen
        textPaint.textSize = side * 0.118f
        strokePaint.strokeWidth = side * 0.012f

        if (needsPermission) {
            textPaint.color = paper.ink
            drawWrapped(canvas, context.getString(R.string.sticky_allow), left, side * 0.5f, width)
            return
        }
        if (n.empty) {
            textPaint.color = paper.ink
            canvas.drawText(fit(context.getString(R.string.sticky_nothing), width), left, side * 0.5f, textPaint)
            drawSun(canvas, side * 0.45f, side * 0.71f, side * 0.075f)
            return
        }

        val gutter = side * 0.075f
        val lineH = side * 0.155f
        var base = side * 0.47f
        for (line in n.lines) {
            val text = fit(lineText(line), width - gutter)
            val ink = if (line.ink == StickyNote.Ink.NEXT) paper.highlight else paper.ink
            textPaint.color = ink
            if (line.ink == StickyNote.Ink.DONE) textPaint.alpha = (StickyNote.DONE_ALPHA * 255).toInt()
            val x = left + gutter
            canvas.drawText(text, x, base, textPaint)
            val tw = textPaint.measureText(text)
            strokePaint.color = textPaint.color
            val mid = base - textPaint.textSize * 0.3f
            when (line.ink) {
                StickyNote.Ink.DONE -> {
                    drawCheck(canvas, left, mid, gutter * 0.8f)
                    // A pen line through it, not quite straight.
                    path.reset()
                    path.moveTo(x - side * 0.01f, mid + side * 0.004f)
                    path.quadTo(x + tw / 2, mid - side * 0.012f, x + tw + side * 0.01f, mid + side * 0.002f)
                    canvas.drawPath(path, strokePaint)
                }
                StickyNote.Ink.NEXT -> {
                    // Underlined by hand, and a dot in the margin.
                    val u = base + side * 0.025f
                    path.reset()
                    path.moveTo(x, u)
                    path.quadTo(x + tw * 0.5f, u + side * 0.014f, x + tw, u - side * 0.004f)
                    canvas.drawPath(path, strokePaint)
                    strokePaint.style = Paint.Style.FILL
                    canvas.drawCircle(left + gutter * 0.35f, mid, side * 0.016f, strokePaint)
                    strokePaint.style = Paint.Style.STROKE
                }
                StickyNote.Ink.PLAIN -> {
                    strokePaint.style = Paint.Style.FILL
                    canvas.drawCircle(left + gutter * 0.35f, mid, side * 0.011f, strokePaint)
                    strokePaint.style = Paint.Style.STROKE
                }
            }
            lineHits += RectF(left, base - lineH * 0.78f, right, base + lineH * 0.22f) to line.event
            textPaint.alpha = 255
            base += lineH
        }
        if (n.more > 0) {
            textPaint.color = paper.ink
            canvas.drawText(context.getString(R.string.sticky_more, n.more), left + gutter, base, textPaint)
        }
    }

    /** [text], cut with "…" to fit [width] in the current pen. */
    private fun fit(text: String, width: Float): String =
        TextUtils.ellipsize(text, textPaint, width, TextUtils.TruncateAt.END).toString()

    /** Up to two lines of [text] from ([x], [y]). */
    private fun drawWrapped(canvas: Canvas, text: String, x: Float, y: Float, width: Float) {
        val cut = textPaint.breakText(text, true, width, null)
        val space = text.lastIndexOf(' ', cut).takeIf { cut < text.length && it > 0 } ?: cut
        canvas.drawText(text.substring(0, space).trim(), x, y, textPaint)
        if (space < text.length) canvas.drawText(fit(text.substring(space).trim(), width), x, y + textPaint.textSize * 1.15f, textPaint)
    }

    /** A pen tick, [size] wide, centred on ([x] + size/2, [y]). */
    private fun drawCheck(canvas: Canvas, x: Float, y: Float, size: Float) {
        path.reset()
        path.moveTo(x, y)
        path.lineTo(x + size * 0.35f, y + size * 0.38f)
        path.quadTo(x + size * 0.6f, y - size * 0.2f, x + size, y - size * 0.55f)
        canvas.drawPath(path, strokePaint)
    }

    /** The clear-day doodle: a little sun, rays and a smile, in marker. */
    private fun drawSun(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        strokePaint.color = paper.ink
        strokePaint.alpha = 255
        canvas.drawCircle(cx, cy, r, strokePaint)
        for (i in 0 until 8) {
            val a = i * PI / 4 + 0.2
            canvas.drawLine(
                cx + (cos(a) * r * 1.45).toFloat(), cy + (sin(a) * r * 1.45).toFloat(),
                cx + (cos(a) * r * 1.95).toFloat(), cy + (sin(a) * r * 1.95).toFloat(), strokePaint,
            )
        }
        strokePaint.style = Paint.Style.FILL
        canvas.drawCircle(cx - r * 0.35f, cy - r * 0.15f, r * 0.1f, strokePaint)
        canvas.drawCircle(cx + r * 0.35f, cy - r * 0.15f, r * 0.1f, strokePaint)
        strokePaint.style = Paint.Style.STROKE
        canvas.drawArc(RectF(cx - r * 0.45f, cy - r * 0.3f, cx + r * 0.45f, cy + r * 0.5f), 25f, 130f, false, strokePaint)
    }

    /** A small tile of speckle and faint fibres, repeated over the paper. */
    private fun paperTexture(): Bitmap {
        val n = 96
        val px = IntArray(n * n)
        val rnd = java.util.Random(7)
        for (i in px.indices) {
            val v = rnd.nextInt(100)
            px[i] = when {
                v < 6 -> Color.argb(18, 90, 70, 20)
                v < 10 -> Color.argb(22, 255, 255, 255)
                else -> Color.TRANSPARENT
            }
        }
        // A few short fibres, running roughly across.
        repeat(14) {
            var x = rnd.nextInt(n)
            val y = rnd.nextInt(n)
            repeat(6 + rnd.nextInt(8)) { k ->
                px[((y + k / 4) % n) * n + x % n] = Color.argb(14, 90, 70, 20)
                x++
            }
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        /** The curled corner's size, as a share of the side. */
        const val CURL = 0.16f
    }
}
