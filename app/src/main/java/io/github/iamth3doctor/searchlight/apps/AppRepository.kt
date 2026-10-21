package io.github.iamth3doctor.searchlight.apps

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Rect
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
        iconLoader.execute {
            val bmp = try {
                renderIcon(entry)
            } catch (e: Exception) {
                null
            } ?: fallbackIcon()
            main.post {
                iconCache.put(entry.key, bmp)
                pendingIcons.remove(entry.key)?.forEach { it(bmp) }
            }
        }
        return null
    }

    private fun renderIcon(entry: AppEntry): Bitmap? {
        val info = infos[entry.key] ?: launcherApps.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(entry.component),
            entry.user,
        ) ?: return null
        val drawable = info.getBadgedIcon(context.resources.displayMetrics.densityDpi)
        return drawable.toBitmap(iconSizePx, iconSizePx)
    }

    private fun fallbackIcon(): Bitmap =
        context.packageManager.defaultActivityIcon.toBitmap(iconSizePx, iconSizePx)

    // ------------------------------------------------------------------ actions

    fun launch(entry: AppEntry, source: View?, from: Context = context) {
        prefs.recordLaunch(entry.key)
        if (entry.isSelf) {
            from.startActivity(Intent(from, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val bounds = source?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        val opts = source?.let {
            ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle()
        }
        try {
            launcherApps.startMainActivity(entry.component, entry.user, bounds, opts)
        } catch (e: Exception) {
            Log.w(TAG, "Launch failed for ${entry.key}", e)
            Toast.makeText(context, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            scheduleReload()
        }
    }

    /**
     * Opens [entry] next to the current app (split-screen) where the system allows it.
     * Android only lets the system launcher start a split from the home screen, so on most
     * devices this opens it full-screen, like [launch]. Other profiles (work) can't be launched
     * this way and just use [launch].
     */
    fun launchAdjacent(entry: AppEntry, from: Context) {
        if (entry.isSelf || entry.user != android.os.Process.myUserHandle()) return launch(entry, null, from)
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
