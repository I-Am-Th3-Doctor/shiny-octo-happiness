package io.github.iamth3doctor.searchlight.iconpack

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.util.Xml
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser
import java.util.concurrent.ConcurrentHashMap

/**
 * One installed icon pack, loaded: its appfilter and its resources. Icons come from, in order:
 * the calendar entry for today (calendar apps the pack lists), the app's exact component in
 * appfilter, and a drawable named after the app's package or class. An app the pack doesn't draw
 * keeps its own system icon. Safe to use from the icon threads.
 */
class IconPack private constructor(
    val packageName: String,
    private val res: Resources,
    private val filter: AppFilter,
) {
    enum class Source { CALENDAR, APPFILTER, NAME_MATCH, FALLBACK }

    private val ids = ConcurrentHashMap<String, Int>()

    /** Is any app themed by today's calendar drawable (so the icons change at midnight)? */
    val hasCalendars: Boolean get() = filter.calendars.isNotEmpty()

    /** Where [pkg]/[cls]'s icon comes from on day [day] of the month. */
    fun source(pkg: String, cls: String, day: Int): Source = find(pkg, cls, day)?.first ?: Source.FALLBACK

    /** Does the pack draw [pkg]/[cls] (on day [day] of the month)? */
    fun covers(pkg: String, cls: String, day: Int): Boolean = find(pkg, cls, day) != null

    /**
     * The pack's own icon for [pkg]/[cls] at [size]px, or null when the pack doesn't draw that app
     * (or its drawable won't load): that app keeps its system icon.
     */
    fun render(pkg: String, cls: String, day: Int, size: Int, density: Int): Bitmap? =
        find(pkg, cls, day)?.let { (_, id) -> drawable(id, density)?.toBitmap(size, size) }

    private fun find(pkg: String, cls: String, day: Int): Pair<Source, Int>? {
        val c = "$pkg/$cls"
        filter.calendars[c]?.let { prefix -> id(prefix + day).takeIf { it != 0 }?.let { return Source.CALENDAR to it } }
        filter.components[c]?.let { name -> id(name).takeIf { it != 0 }?.let { return Source.APPFILTER to it } }
        for (name in AppFilter.nameCandidates(pkg, cls)) id(name).takeIf { it != 0 }?.let { return Source.NAME_MATCH to it }
        return null
    }

    @android.annotation.SuppressLint("DiscouragedApi") // packs are looked up by name: that's the format
    private fun id(name: String): Int = ids.getOrPut(name) { res.getIdentifier(name, "drawable", packageName) }

    private fun drawable(id: Int, density: Int): Drawable? = try {
        res.getDrawableForDensity(id, density, null)
    } catch (e: Exception) {
        null
    }

    companion object {
        /** The standard ways an app says it's an icon pack. */
        val ACTIONS = listOf("org.adw.launcher.THEMES", "com.gau.go.launcherex.theme")

        /** Does [pkg] ship an appfilter (as an xml resource or an asset)? Cheap; no parsing. */
        @android.annotation.SuppressLint("DiscouragedApi")
        fun hasAppFilter(context: Context, pkg: String): Boolean = try {
            val res = context.packageManager.getResourcesForApplication(pkg)
            res.getIdentifier("appfilter", "xml", pkg) != 0 || res.assets.list("")?.contains("appfilter.xml") == true
        } catch (e: Exception) {
            false
        }

        /** Loads and parses [pkg]. @throws Exception when it isn't installed or its appfilter is unusable. */
        @android.annotation.SuppressLint("DiscouragedApi")
        fun load(context: Context, pkg: String): IconPack {
            val res = context.packageManager.getResourcesForApplication(pkg)
            val xmlId = res.getIdentifier("appfilter", "xml", pkg)
            val filter = if (xmlId != 0) {
                val parser = res.getXml(xmlId)
                try { AppFilter.parse(elements(parser)) } finally { parser.close() }
            } else {
                res.assets.open("appfilter.xml").use { input ->
                    val parser = Xml.newPullParser().apply { setInput(input, null) }
                    AppFilter.parse(elements(parser))
                }
            }
            return IconPack(pkg, res, filter)
        }

        private fun elements(p: XmlPullParser): Sequence<AppFilter.Element> = sequence {
            while (true) {
                when (p.next()) {
                    XmlPullParser.END_DOCUMENT -> return@sequence
                    XmlPullParser.START_TAG -> yield(
                        AppFilter.Element(p.name, (0 until p.attributeCount).associate { p.getAttributeName(it) to p.getAttributeValue(it) })
                    )
                }
            }
        }
    }
}
