package io.github.iamth3doctor.searchlight.iconpack

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Searchlight's own icon theme, built in: the PNGs in assets/icontheme and the mapping in its
 * theme.txt (app → icon file, its move on Home, and whether it's live). See theme.txt for the
 * format and how to add an icon. Apps the theme doesn't name keep their own icon. Safe to use from
 * the icon threads.
 */
class IconTheme private constructor(private val context: Context, val icons: Map<String, Icon>) {

    /** How an icon comes alive on Home: the generic moves, and the signature ones. */
    enum class Move(val signature: Boolean) {
        SPIN(false), SWING(false), BOUNCE(false), FLIP(false),
        RECORD(true), RING(true), SHUTTER(true), GEAR(true), DROP(true),
    }

    /** A live icon: drawn over its PNG by Searchlight, as time passes. */
    enum class Live { CLOCK, CALENDAR }

    /** One app's line: its art ([file], null for a move only), its [move], and whether it's [live]. */
    class Icon(val file: String?, val move: Move, val live: Live?)

    private val art = ConcurrentHashMap<String, Bitmap>()

    /** The theme's icon for [component] (its exact component first, then its package), or null. */
    fun find(component: ComponentName): Icon? =
        icons[component.flattenToString()] ?: icons[component.packageName]

    /** The icon files the theme names (distinct). */
    val files: Set<String> get() = icons.values.mapNotNull { it.file }.toSet()

    /**
     * [component]'s move on Home: the one its line gives (keyed by the app, so it plays whatever
     * art shows for it: the theme's, a pack's), else one of the generic moves, always the same one.
     */
    fun moveFor(component: ComponentName): Move = find(component)?.move ?: genericMove(component)

    /**
     * [icon] at [size]px at time [now]: a live clock shows that time (10:10 when [still]), a live
     * calendar that date. Null when its PNG won't load (the app's own icon is used then).
     */
    fun render(icon: Icon, size: Int, now: Long, still: Boolean): Bitmap? {
        val base = art(icon.file ?: return null) ?: return null
        val out = Bitmap.createScaledBitmap(base, size, size, true).let {
            if (it === base) it.copy(Bitmap.Config.ARGB_8888, true) else it
        }
        when (icon.live) {
            Live.CLOCK -> drawHands(Canvas(out), size.toFloat(), if (still) STILL_TIME else timeOf(now))
            Live.CALENDAR -> drawDate(Canvas(out), size.toFloat(), now)
            null -> Unit
        }
        return out
    }

    private fun art(file: String): Bitmap? = art[file] ?: try {
        context.assets.open("$DIR/$file").use { BitmapFactory.decodeStream(it) }?.also { art[file] = it }
    } catch (e: Exception) {
        Log.w(TAG, "Icon theme: $file won't load", e)
        null
    }

    /** The clock's hands for [hm] (hours, minutes), on the face drawn in clock.png. */
    private fun drawHands(c: Canvas, s: Float, hm: Pair<Int, Int>) {
        val (hour, minute) = hm
        // Paints of its own each time: two icon threads may draw at once.
        val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cx = s * CLOCK_CX
        val cy = s * CLOCK_CY
        val r = s * CLOCK_R
        val (ha, ma) = handAngles(hour, minute)
        fun hand(angle: Double, len: Float, width: Float, color: Int) {
            val x = cx + (len * Math.sin(Math.toRadians(angle))).toFloat()
            val y = cy - (len * Math.cos(Math.toRadians(angle))).toFloat()
            // A soft shadow below each hand, then the hand, then a fine highlight along it: the same light from the top.
            handPaint.strokeWidth = width
            handPaint.color = 0x40000000
            c.drawLine(cx, cy + width * 0.45f, x, y + width * 0.45f, handPaint)
            handPaint.color = color
            c.drawLine(cx, cy, x, y, handPaint)
            handPaint.strokeWidth = width * 0.3f
            handPaint.color = 0x55FFFFFF
            c.drawLine(cx, cy - width * 0.18f, x, y - width * 0.18f, handPaint)
        }
        hand(ha, r * 0.52f, s * 0.05f, 0xFF262B36.toInt())
        hand(ma, r * 0.78f, s * 0.035f, 0xFF262B36.toInt())
        fillPaint.color = 0xFFD93025.toInt()
        c.drawCircle(cx, cy, s * 0.035f, fillPaint)
        fillPaint.color = 0x88FFFFFF.toInt()
        c.drawCircle(cx - s * 0.008f, cy - s * 0.01f, s * 0.012f, fillPaint)
    }

    /** Today's date on calendar.png: the month on the red band, the day on the page. */
    private fun drawDate(c: Canvas, s: Float, now: Long) {
        val (month, day) = dateText(now)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val bold = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        fillPaint.typeface = bold
        fillPaint.textAlign = Paint.Align.CENTER
        // Where there's room (any size the launcher draws), the month too.
        if (s >= 48) {
            fillPaint.textSize = s * 0.1f
            fillPaint.color = 0xFFFFFFFF.toInt()
            fillPaint.setShadowLayer(s * 0.01f, 0f, s * 0.006f, 0x66000000)
            c.drawText(month, s / 2, s * CAL_BAND_Y + fillPaint.textSize * 0.36f, fillPaint)
        }
        fillPaint.textSize = s * 0.36f
        fillPaint.color = 0xFF262B36.toInt()
        fillPaint.setShadowLayer(s * 0.012f, 0f, s * 0.008f, 0x33000000)
        c.drawText(day, s / 2, s * CAL_PAGE_Y + fillPaint.textSize * 0.36f, fillPaint)
        fillPaint.clearShadowLayer()
    }

    companion object {
        private const val TAG = "IconTheme"
        const val DIR = "icontheme"
        const val MAPPING = "theme.txt"

        /** The face on clock.png and the band and page on calendar.png, as fractions of the icon. */
        const val CLOCK_CX = 0.5f
        const val CLOCK_CY = 400f / 768
        const val CLOCK_R = 200f / 768
        const val CAL_BAND_Y = 232f / 768
        const val CAL_PAGE_Y = 474f / 768

        /** In theme.txt, "-" in place of a file: a move for the app, no art of its own. */
        const val NO_ART = "-"

        private val GENERIC = listOf(Move.SPIN, Move.SWING, Move.BOUNCE, Move.FLIP)

        /** A generic move for an app with none of its own: the same one every time for that app. */
        fun genericMove(component: ComponentName): Move =
            GENERIC[Math.floorMod(component.flattenToString().hashCode(), GENERIC.size)]

        /** The still face: 10:10. */
        val STILL_TIME = 10 to 10

        /** Hours (0-23) and minutes of [now], here. */
        fun timeOf(now: Long): Pair<Int, Int> {
            val c = Calendar.getInstance().apply { timeInMillis = now }
            return c.get(Calendar.HOUR_OF_DAY) to c.get(Calendar.MINUTE)
        }

        /** The hour and minute hands' angles, clockwise from 12, in degrees. */
        fun handAngles(hour: Int, minute: Int): Pair<Double, Double> =
            ((hour % 12) * 30.0 + minute * 0.5) to minute * 6.0

        /** The calendar icon's month ("NOV") and day ("26") for [now], here. */
        fun dateText(now: Long): Pair<String, String> {
            val c = Calendar.getInstance().apply { timeInMillis = now }
            val month = c.getDisplayName(Calendar.MONTH, Calendar.SHORT, Locale.getDefault())?.uppercase(Locale.getDefault()) ?: ""
            return month.take(3) to c.get(Calendar.DAY_OF_MONTH).toString()
        }

        /** Loads the bundled theme. Never fails: a theme that won't read is an empty one. */
        fun load(context: Context): IconTheme {
            val icons = try {
                context.assets.open("$DIR/$MAPPING").bufferedReader().use { parse(it.readText()) }
            } catch (e: Exception) {
                Log.w(TAG, "Icon theme mapping won't load", e)
                emptyMap()
            }
            return IconTheme(context.applicationContext, icons)
        }

        /**
         * theme.txt's lines: "<app> <file> <move> [clock|calendar]", # for notes. A line that
         * doesn't read is skipped (logged); an unknown move falls back to a gentle bounce.
         */
        fun parse(text: String): Map<String, Icon> {
            val out = LinkedHashMap<String, Icon>()
            for (raw in text.lineSequence()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty()) continue
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2 || !(parts[1].endsWith(".png") || parts[1] == NO_ART)) {
                    Log.w(TAG, "Icon theme: skipped \"$raw\"")
                    continue
                }
                val key = if ('/' in parts[0]) {
                    ComponentName.unflattenFromString(parts[0])?.flattenToString() ?: continue
                } else parts[0]
                val move = parts.getOrNull(2)?.let { m -> Move.entries.firstOrNull { it.name.equals(m, ignoreCase = true) } } ?: Move.BOUNCE
                val live = parts.getOrNull(3)?.let { l -> Live.entries.firstOrNull { it.name.equals(l, ignoreCase = true) } }
                out[key] = Icon(parts[1].takeIf { it != NO_ART }, move, live)
            }
            return out
        }
    }
}
