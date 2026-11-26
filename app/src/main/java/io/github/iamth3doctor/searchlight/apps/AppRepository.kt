package io.github.iamth3doctor.searchlight.apps

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.Toast
import androidx.core.graphics.drawable.toBitmap
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.SettingsActivity
import io.github.iamth3doctor.searchlight.data.Prefs
import io.github.iamth3doctor.searchlight.iconpack.IconPack
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Source of truth for installed apps.
 *
 * Cold start is instant because the last known list (labels only) is read from a tiny file
 * synchronously; the live list from [LauncherApps] replaces it a moment later on a background
 * thread. Icons are rendered lazily, off the main thread, and kept in an LRU cache.
 *
 * All public methods must be called on the main thread.
 */
class AppRepository(private val context: Context, private val prefs: Prefs) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val iconLoader = Executors.newFixedThreadPool(2)
    private val cacheFile = File(context.filesDir, "apps.cache")

    private val infos = ConcurrentHashMap<String, LauncherActivityInfo>()
    private val iconCache = LruCache<String, Bitmap>(400)
    private val pendingIcons = HashMap<String, MutableList<(Bitmap) -> Unit>>()
    // Rendered at the largest size an app icon is shown (favorites grid); smaller uses scale down.
    private val iconSizePx = context.resources.getDimensionPixelSize(R.dimen.size_grid_icon)

    private val listeners = mutableListOf<() -> Unit>()

    /** Sorted by label. Replaced wholesale (never mutated), so it is safe to hold a reference. */
    var apps: List<AppEntry> = emptyList()
        private set

    private var byKey: Map<String, AppEntry> = emptyMap()

    fun start() {
        setApps(readCache())
        launcherApps.registerCallback(packageCallback, main)
        reloadNow()
    }

    fun find(key: String): AppEntry? = byKey[key]

    /** The app [pkg] opens as (its personal-profile entry first); null once it's uninstalled. */
    fun findByPackage(pkg: String): AppEntry? =
        apps.filter { it.packageName == pkg && !it.isSelf }.minByOrNull { if (it.user == Process.myUserHandle()) 0 else 1 }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    // ------------------------------------------------------------------ loading

    private val reloadRunnable = Runnable { reloadNow() }

    /** Package events tend to arrive in bursts (updates = remove + add); coalesce them. */
    private fun scheduleReload() {
        main.removeCallbacks(reloadRunnable)
        main.postDelayed(reloadRunnable, 300)
    }

    private fun reloadNow() {
        loader.execute {
            val list = try {
                queryAll()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query apps", e)
                return@execute
            }
            main.post { setApps(list) }
            writeCache(list)
        }
    }

    private fun setApps(list: List<AppEntry>) {
        apps = list
        byKey = list.associateBy { it.key }
        listeners.toList().forEach { it() }
    }

    private fun queryAll(): List<AppEntry> {
        val own = context.packageName
        val result = ArrayList<AppEntry>()
        for (user in userManager.userProfiles) {
            val serial = userManager.getSerialNumberForUser(user)
            val activities = try {
                launcherApps.getActivityList(null, user)
            } catch (e: SecurityException) {
                emptyList()
            }
            for (info in activities) {
                val self = info.componentName.packageName == own
                val label = if (self) context.getString(R.string.self_label) else info.label?.toString().orEmpty()
                if (label.isBlank()) continue
                val entry = AppEntry(info.componentName, user, serial, label.trim(), self)
                infos[entry.key] = info
                result += entry
            }
        }
        result.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
        return result
    }

    private fun readCache(): List<AppEntry> = try {
        if (!cacheFile.exists()) emptyList() else {
            val own = context.packageName
            cacheFile.readLines().mapNotNull { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@mapNotNull null
                val serial = parts[0].toLongOrNull() ?: return@mapNotNull null
                val user = userManager.getUserForSerialNumber(serial) ?: return@mapNotNull null
                val cn = ComponentName.unflattenFromString(parts[1]) ?: return@mapNotNull null
                AppEntry(cn, user, serial, parts[2], cn.packageName == own)
            }
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun writeCache(list: List<AppEntry>) {
        try {
            val tmp = File(cacheFile.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                for (a in list) {
                    val label = a.label.replace('\t', ' ').replace('\n', ' ')
                    w.append("${a.userSerial}\t${a.component.flattenToString()}\t$label\n")
                }
            }
            tmp.renameTo(cacheFile)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write app cache", e)
        }
    }

    private val packageCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::changed)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::changed)

        private fun changed(pkg: String) {
            iconCache.snapshot().keys.filter { it.contains("/$pkg/") }.forEach { iconCache.remove(it) }
            scheduleReload()
        }
    }

    // ------------------------------------------------------------------ icons

    /**
     * Returns the icon immediately if cached; otherwise returns null and calls [onReady] on the
     * main thread once it has been rendered.
     */
    fun icon(entry: AppEntry, onReady: (Bitmap) -> Unit): Bitmap? {
        iconCache.get(entry.key)?.let { return it }
        pendingIcons[entry.key]?.let { it += onReady; return null }
        pendingIcons[entry.key] = mutableListOf(onReady)
        startRender(entry)
        return null
    }

    private fun startRender(entry: AppEntry) {
        val generation = iconGeneration
        iconLoader.execute {
            val bmp = try {
                renderIcon(entry)
            } catch (e: Exception) {
                null
            } ?: fallbackIcon()
            main.post {
                // The icon pack changed while this was drawing: draw it again with the new one.
                if (generation != iconGeneration) return@post startRender(entry)
                iconCache.put(entry.key, bmp)
                pendingIcons.remove(entry.key)?.forEach { it(bmp) }
            }
        }
    }

    /** The chosen icon pack (null: the system's icons). Setting it redraws every icon. Main thread. */
    @Volatile var iconPack: IconPack? = null
        set(value) {
            field = value
            invalidateIcons()
        }

    /**
     * Searchlight's own icon theme, when it's on (null: off). It comes first for the apps it maps;
     * the rest keep the icon pack's or the system's icon. Setting it redraws every icon. Main thread.
     */
    @Volatile var iconTheme: io.github.iamth3doctor.searchlight.iconpack.IconTheme? = null
        set(value) {
            field = value
            invalidateIcons()
        }

    /** Whether the theme's live icons hold still (the clock at 10:10): animations are paused. */
    @Volatile var iconsStill: () -> Boolean = { false }

    /**
     * How [entry]'s icon comes alive on Home while the theme is on (null: off): its own move from
     * the theme, keyed by the app, so it plays on whatever icon shows (the theme's, a pack's, the
     * system's), else a generic one. Main thread.
     */
    fun moveFor(entry: AppEntry): io.github.iamth3doctor.searchlight.iconpack.IconTheme.Move? =
        iconTheme?.moveFor(entry.component)

    /**
     * Is [entry]'s icon themed (not its system icon)? The live clock and calendar; with a pack
     * applied, the apps it draws; otherwise the apps the theme has a file for. Main thread.
     */
    fun isThemed(entry: AppEntry): Boolean {
        val theme = iconTheme ?: return false
        val line = theme.find(entry.component)
        val pack = iconPack
        return when {
            line?.live != null -> true
            pack != null -> packCovers(pack, entry)
            else -> line?.file != null
        }
    }

    private fun packCovers(pack: IconPack, entry: AppEntry): Boolean = try {
        pack.covers(entry.component.packageName, entry.component.className,
            java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH))
    } catch (e: Exception) {
        false
    }

    /** The theme's icon for [entry] while the theme is on, or null. */
    fun themedIcon(entry: AppEntry): io.github.iamth3doctor.searchlight.iconpack.IconTheme.Icon? = iconTheme?.find(entry.component)

    /** Forget [entry]'s drawn icon (a live icon moved on): the next [icon] draws it afresh. Main thread. */
    fun forgetIcon(entry: AppEntry) {
        iconCache.remove(entry.key)
    }

    private var iconGeneration = 0

    /** Forget every drawn icon and tell the lists to draw them again. Main thread. */
    fun invalidateIcons() {
        iconGeneration++
        iconCache.evictAll()
        listeners.toList().forEach { it() }
    }

    private fun renderIcon(entry: AppEntry): Bitmap? {
        val info = infos[entry.key] ?: launcherApps.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(entry.component),
            entry.user,
        ) ?: return null
        val dpi = context.resources.displayMetrics.densityDpi
        // Work apps keep their briefcase badge, as the system's icons have it.
        fun badged(b: Bitmap): Bitmap = if (entry.user == Process.myUserHandle()) b
            else context.packageManager.getUserBadgedIcon(BitmapDrawable(context.resources, b), entry.user).toBitmap(iconSizePx, iconSizePx)
        val theme = iconTheme
        val line = theme?.find(entry.component)
        fun themeArt(): Bitmap? = try {
            if (theme == null || line == null) null else theme.render(line, iconSizePx, System.currentTimeMillis(), iconsStill())
        } catch (e: Exception) {
            null // the system's icon below, never a blank
        }
        // 1. The live clock and calendar: always Searchlight's own, even with a pack applied.
        if (line?.live != null) themeArt()?.let { return badged(it) }
        val pack = iconPack
        if (pack != null) {
            // 2. A pack applied: its icons in place of the theme's; an app it doesn't draw keeps its system icon.
            val day = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_MONTH)
            val drawn = try {
                pack.render(entry.component.packageName, entry.component.className, day, iconSizePx, dpi)
            } catch (e: Exception) {
                null
            }
            if (drawn != null) return badged(drawn)
        } else {
            // 3. The theme's own art.
            themeArt()?.let { return badged(it) }
        }
        return info.getBadgedIcon(dpi).toBitmap(iconSizePx, iconSizePx)
    }

    private fun fallbackIcon(): Bitmap =
        context.packageManager.defaultActivityIcon.toBitmap(iconSizePx, iconSizePx)

    // ------------------------------------------------------------------ actions

    /**
     * Opens [entry]; whether it opened. A failure says so in a toast, unless [quiet] (the caller
     * has somewhere else to go instead).
     */
    fun launch(entry: AppEntry, source: View?, from: Context = context, quiet: Boolean = false): Boolean {
        prefs.recordLaunch(entry.key)
        if (entry.isSelf) {
            from.startActivity(Intent(from, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
        val bounds = source?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        val opts = source?.let {
            ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        return try {
            launcherApps.startMainActivity(entry.component, entry.user, bounds, opts)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Launch failed for ${entry.key}", e)
            if (!quiet) Toast.makeText(context, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            scheduleReload()
            false
        }
    }

    /**
     * Opens [entry] next to the current app (split-screen) where the system allows it.
     * Android only lets the system launcher start a split from the home screen, so on most
     * devices this opens it full-screen, like [launch]. Other profiles (work) can't be launched
     * this way and just use [launch].
     */
    fun launchAdjacent(entry: AppEntry, from: Context) {
        if (entry.isSelf || entry.user != android.os.Process.myUserHandle()) {
            launch(entry, null, from)
            return
        }
        prefs.recordLaunch(entry.key)
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(entry.component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        try {
            from.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Adjacent launch failed for ${entry.key}", e)
            launch(entry, null, from)
        }
    }

    fun openAppInfo(entry: AppEntry, source: View?) {
        val bounds = source?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        try {
            launcherApps.startAppDetailsActivity(entry.component, entry.user, bounds, null)
        } catch (e: Exception) {
            Log.w(TAG, "App info failed for ${entry.key}", e)
        }
    }

    fun canUninstall(entry: AppEntry) = !entry.isSelf && entry.user == Process.myUserHandle()

    fun uninstall(entry: AppEntry, from: Context) {
        val intent = Intent(Intent.ACTION_DELETE, Uri.fromParts("package", entry.packageName, null))
        from.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val TAG = "AppRepository"
    }
}
