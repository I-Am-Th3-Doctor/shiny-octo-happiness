package io.github.iamth3doctor.searchlight.iconpack

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.data.Prefs
import java.util.concurrent.Executors

/**
 * The installed icon packs and the chosen one. Choosing applies at once (every icon redraws);
 * a pack that won't load is refused and the previous choice stays. If the chosen pack is
 * uninstalled, icons go back to the system's without a word; if it's updated, it's reloaded.
 * Calendar icons follow the date.
 */
class IconPacks(private val context: Context, private val prefs: Prefs, private val repo: AppRepository) {

    class Info(val packageName: String, val label: CharSequence, val icon: Drawable?)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val listeners = mutableListOf<() -> Unit>()

    /** The chosen pack's package, or null for the system's icons. */
    val selected: String? get() = prefs.iconPack

    fun start() {
        val saved = prefs.iconPack
        if (saved != null) worker.execute {
            val pack = tryLoad(saved)
            main.post {
                when {
                    prefs.iconPack != saved -> Unit // chosen again meanwhile
                    pack != null -> repo.iconPack = pack
                    !installed(saved) -> prefs.iconPack = null // uninstalled while we weren't running
                    // Installed but unreadable: system icons for now, the choice stays for when it's fixed.
                }
            }
        }
        ContextCompat.registerReceiver(context, packages, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(context, dateChanged, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    /** Called when packs are installed or removed (the Settings list refreshes). */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /**
     * Every installed app that says it's an icon pack and ships an appfilter, A-Z. Slow-ish
     * (it opens each pack): call it off the main thread.
     */
    fun installedPacks(): List<Info> {
        val pm = context.packageManager
        return IconPack.ACTIONS.flatMap { action ->
            try {
                pm.queryIntentActivities(Intent(action), PackageManager.MATCH_ALL)
            } catch (e: Exception) {
                emptyList()
            }
        }.map { it.activityInfo.packageName }.distinct()
            .filter { it != context.packageName && IconPack.hasAppFilter(context, it) }
            .mapNotNull { pkg ->
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    Info(pkg, pm.getApplicationLabel(ai), runCatching { pm.getApplicationIcon(ai) }.getOrNull())
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.label.toString().lowercase() }
    }

    /**
     * Uses [pkg] (null: the system's icons). [done] gets false, on the main thread, when the pack
     * won't load; the previous choice is then kept.
     */
    fun select(pkg: String?, done: (Boolean) -> Unit) {
        if (pkg == null) {
            prefs.iconPack = null
            repo.iconPack = null
            done(true)
            return
        }
        worker.execute {
            val pack = tryLoad(pkg)
            main.post {
                if (pack != null) {
                    prefs.iconPack = pkg
                    repo.iconPack = pack
                }
                done(pack != null)
            }
        }
    }

    private fun tryLoad(pkg: String): IconPack? = try {
        IconPack.load(context, pkg)
    } catch (e: Throwable) { // a broken pack must never take the launcher down
        Log.w(TAG, "Icon pack $pkg won't load", e)
        null
    }

    private fun installed(pkg: String) = try {
        context.packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    private val packages = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val pkg = intent.data?.schemeSpecificPart ?: return
            val replacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            if (pkg == prefs.iconPack) when (intent.action) {
                Intent.ACTION_PACKAGE_REMOVED -> if (!replacing) {
                    prefs.iconPack = null
                    repo.iconPack = null
                }
                Intent.ACTION_PACKAGE_REPLACED, Intent.ACTION_PACKAGE_CHANGED -> worker.execute {
                    // Its new version: reload (system icons if the update broke it).
                    val pack = tryLoad(pkg)
                    main.post { if (prefs.iconPack == pkg) repo.iconPack = pack }
                }
            }
            listeners.toList().forEach { it() }
        }
    }

    private val dateChanged = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (repo.iconPack?.hasCalendars == true) repo.invalidateIcons()
        }
    }

    private companion object {
        const val TAG = "IconPacks"
    }
}
