package io.github.iamth3doctor.searchlight.apps

import android.app.ActivityOptions
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.util.Log
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.data.Prefs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * What the long-press menu needs per app, cached so it opens instantly: the app's own shortcuts
 * (manifest, dynamic and pinned, from LauncherApps, in that app's profile) with their icons, and
 * whether it has widgets. Filled in the background for every app once the app list is known, and
 * refreshed per package when an app is installed, updated or removed or changes its shortcuts.
 *
 * Android only hands shortcuts to the default home app, so until Searchlight is the default the
 * shortcut lists are empty; becoming the default refills the cache.
 */
class AppShortcuts(private val context: Context, private val repo: AppRepository, private val prefs: Prefs) {

    class Shortcut(val info: ShortcutInfo, val label: CharSequence, val icon: Bitmap?)

    class Menu(val shortcuts: List<Shortcut>, val hasWidgets: Boolean)

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val widgets = AppWidgetManager.getInstance(context)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cache = ConcurrentHashMap<String, Menu>()
    private val iconPx = context.resources.getDimensionPixelSize(R.dimen.size_menu_icon)
    @Volatile private var hostAccess = false

    private fun key(pkg: String, user: UserHandle) = "${user.hashCode()}/$pkg"

    fun start() {
        launcherApps.registerCallback(callback, main)
        repo.addListener(::warm)
        warm()
    }

    /** The menu for [entry]: from the cache, or read now if it isn't there yet. Main thread. */
    fun menuFor(entry: AppEntry): Menu = cache[key(entry.packageName, entry.user)] ?: load(entry.packageName, entry.user)

    /** Call on resume: becoming (or no longer being) the default home changes what we may read. */
    fun refreshIfAccessChanged() {
        if (hasHostAccess() != hostAccess) warm()
    }

    /** Fills the cache, in the background, for every app not in it yet. Main thread. */
    private fun warm() {
        val access = hasHostAccess()
        if (access != hostAccess) {
            hostAccess = access
            cache.clear()
        }
        val todo = repo.apps.asSequence().filter { !it.isSelf }.map { it.packageName to it.user }.distinct()
            .filter { (p, u) -> !cache.containsKey(key(p, u)) }.toList()
        if (todo.isNotEmpty()) worker.execute { todo.forEach { (p, u) -> load(p, u) } }
    }

    private fun hasHostAccess() = try {
        launcherApps.hasShortcutHostPermission()
    } catch (e: Exception) {
        false
    }

    private fun load(pkg: String, user: UserHandle): Menu {
        val menu = Menu(query(pkg, user), hasWidgets(pkg, user))
        cache[key(pkg, user)] = menu
        return menu
    }

    private fun query(pkg: String, user: UserHandle): List<Shortcut> {
        if (!hasHostAccess()) return emptyList()
        val q = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
        )
        val found = try {
            launcherApps.getShortcuts(q, user)
        } catch (e: Exception) { // locked work profile, or no longer the default home
            null
        } ?: return emptyList()
        val dpi = context.resources.displayMetrics.densityDpi
        return pick(found, { it.id }, { it.isEnabled }, ::kind, { it.rank }).map { s ->
            val icon = try {
                launcherApps.getShortcutIconDrawable(s, dpi)?.toBitmap(iconPx, iconPx)
            } catch (e: Exception) {
                null
            }
            Shortcut(s, s.shortLabel?.takeIf { it.isNotBlank() } ?: s.longLabel ?: "", icon)
        }
    }

    private fun kind(s: ShortcutInfo) = when {
        s.isDeclaredInManifest -> 0
        s.isDynamic -> 1
        else -> 2 // pinned only
    }

    private fun hasWidgets(pkg: String, user: UserHandle) = try {
        widgets.getInstalledProvidersForPackage(pkg, user).isNotEmpty()
    } catch (e: Exception) {
        false
    }

    /** Opens [shortcut]; if the app refuses (disabled, removed, locked profile) opens the app itself. */
    fun launch(entry: AppEntry, shortcut: Shortcut, source: View?) {
        val bounds = source?.let {
            val loc = IntArray(2)
            it.getLocationOnScreen(loc)
            Rect(loc[0], loc[1], loc[0] + it.width, loc[1] + it.height)
        }
        val opts = source?.let { ActivityOptions.makeClipRevealAnimation(it, 0, 0, it.width, it.height).toBundle() }
        try {
            launcherApps.startShortcut(shortcut.info, bounds, opts)
            prefs.recordLaunch(entry.key)
        } catch (e: Exception) {
            Log.w(TAG, "Shortcut ${shortcut.info.id} failed for ${entry.key}; opening the app", e)
            repo.launch(entry, source)
        }
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onShortcutsChanged(packageName: String, shortcuts: MutableList<ShortcutInfo>, user: UserHandle) =
            reload(packageName, user)
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload(packageName, user)
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload(packageName, user)
        override fun onPackageRemoved(packageName: String, user: UserHandle) { cache.remove(key(packageName, user)) }
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach { reload(it, user) }
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach { cache.remove(key(it, user)) }

        private fun reload(pkg: String, user: UserHandle) {
            cache.remove(key(pkg, user))
            worker.execute { load(pkg, user) }
        }
    }

    companion object {
        private const val TAG = "AppShortcuts"
        /** As many as the stock launcher shows. */
        const val MAX_SHORTCUTS = 4

        /**
         * The shortcuts the menu shows: enabled ones, manifest first, then dynamic, then pinned
         * only, each by the app's own rank; at most [MAX_SHORTCUTS].
         */
        fun <T> pick(all: List<T>, id: (T) -> String, enabled: (T) -> Boolean, kind: (T) -> Int, rank: (T) -> Int): List<T> =
            all.filter(enabled).distinctBy(id).sortedWith(compareBy(kind).thenBy(rank)).take(MAX_SHORTCUTS)
    }
}
