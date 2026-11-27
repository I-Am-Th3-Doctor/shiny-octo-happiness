package io.github.iamth3doctor.searchlight.iconpack

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Searchlight's own icon theme, built in: the PNGs in assets/icontheme and the mapping in its
 * theme.txt (app → icon file, its move on Home, and whether it's live). See theme.txt for the
 * format and how to add an icon. It ships with no icons of its own: apps keep their system icon
 * until one is added. The live clock and calendar are drawn here, frosted, in natural colors.
 * Safe to use from the icon threads.
 */
class IconTheme private constructor(
    val icons: Map<String, Icon>,
    /** Opens an icon file of the theme (the assets folder; tests may stand in). */
    private val open: (String) -> Bitmap?,
    /** Every app's move, from searchlight-icon-actions.md. */
    val actions: IconActions,
) {

    /**
     * How an icon comes alive on Home: the 16 moves of searchlight-icon-actions.md (the first
     * four are also the generic ones), and two older ones theme.txt may still name.
     */
    enum class Move(val signature: Boolean) {
        SPIN(false), SWING(false), BOUNCE(false), FLIP(false),
        RING(true), SHUTTER(true), POP(true), WOBBLE(true), PULSE(true), SLIDE(true), DROP(true),
        CHAT(true), TICK(true), WAVE(true), FLASH(true), ZOOM(true),
        RECORD(true), GEAR(true),
    }

    /** A live icon: drawn by Searchlight, as time passes. */
    enum class Live { CLOCK, CALENDAR }

    /**
     * One app's line: its art ([file], null for none), a [move] of its own (null: the app's move
     * from searchlight-icon-actions.md), and whether it's [live].
     */
    class Icon(val file: String?, val move: Move?, val live: Live?)

    private val art = ConcurrentHashMap<String, Bitmap>()

    /** The theme's icon for [component] (its exact component first, then its package), or null. */
    fun find(component: ComponentName): Icon? =
        icons[component.flattenToString()] ?: icons[component.packageName]

    /** The icon files the theme names (distinct). */
    val files: Set<String> get() = icons.values.mapNotNull { it.file }.toSet()

    /**
     * The move on Home of the app at [component], labelled [label]: the one its theme.txt line
     * gives, else its row in searchlight-icon-actions.md (by component, then label), else one of
     * the generic moves, always the same one. Keyed by the app, so it plays whatever art shows for
     * it: the theme's, a pack's, the system's.
     */
    fun moveFor(component: ComponentName, label: String? = null): Move =
        find(component)?.move ?: actions.moveFor(component.flattenToString(), label) ?: genericMove(component)

    /**
     * [icon] at [size]px at time [now]: a live clock shows that time (10:10 when [still]), a live
     * calendar that date; otherwise its PNG. Null when it has no PNG, or it won't load (the app
     * keeps its own icon then).
     */
    fun render(icon: Icon, size: Int, now: Long, still: Boolean): Bitmap? {
        when (icon.live) {
            Live.CLOCK -> return LiveIcons.clock(size, if (still) STILL_TIME else timeOf(now))
            Live.CALENDAR -> return LiveIcons.calendar(size, now)
            null -> Unit
        }
        val base = art(icon.file ?: return null) ?: return null
        return Bitmap.createScaledBitmap(base, size, size, true).let {
            if (it === base) it.copy(Bitmap.Config.ARGB_8888, false) else it
        }
    }

    private fun art(file: String): Bitmap? = art[file] ?: try {
        open(file)?.also { art[file] = it }
    } catch (e: Exception) {
        Log.w(TAG, "Icon theme: $file won't load", e)
        null
    }

    companion object {
        private const val TAG = "IconTheme"
        const val DIR = "icontheme"
        const val MAPPING = "theme.txt"

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
            val actions = try {
                context.assets.open("$DIR/${IconActions.FILE}").bufferedReader().use { IconActions.parse(it.readText()) }
                    .also { a -> a.skipped.forEach { Log.w(TAG, "Icon actions: not a move, a generic one instead: $it") } }
            } catch (e: Exception) {
                Log.w(TAG, "Icon actions won't load", e)
                IconActions.EMPTY
            }
            val assets = context.applicationContext.assets
            return IconTheme(icons, { file -> assets.open("$DIR/$file").use { BitmapFactory.decodeStream(it) } }, actions)
        }

        /**
         * A theme from [text] (theme.txt's format) whose icon files come from [open], with the
         * moves of [actions] (tests).
         */
        fun of(text: String, open: (String) -> Bitmap?, actions: IconActions = IconActions.EMPTY): IconTheme =
            IconTheme(parse(text), open, actions)

        /**
         * theme.txt's lines: "<app> <file> [<move>|-] [clock|calendar]", # for notes. A line that
         * doesn't read is skipped (logged); "-" or no move leaves the app's own move (the actions
         * table's); an unknown one falls back to a gentle bounce.
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
                // The move may be left out ("-", or straight to the live word).
                val rest = parts.drop(2).toMutableList()
                val live = rest.lastOrNull()?.let { l -> Live.entries.firstOrNull { it.name.equals(l, ignoreCase = true) } }
                if (live != null) rest.removeAt(rest.lastIndex)
                val move = rest.firstOrNull()?.takeIf { it != NO_ART }?.let { m -> Move.entries.firstOrNull { it.name.equals(m, ignoreCase = true) } ?: Move.BOUNCE }
                out[key] = Icon(parts[1].takeIf { it != NO_ART }, move, live)
            }
            return out
        }
    }
}
