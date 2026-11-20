package com.childsplay.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.Collator
import java.util.concurrent.ConcurrentHashMap

/** One launchable activity. [key] is stable across launches and is what things store. */
data class AppEntry(
    val key: String,
    val label: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val category: Int,
    val isWorkProfile: Boolean,
)

/**
 * Source of truth for installed, launchable apps. Lists come from [LauncherApps] so work-profile
 * apps show up too, and the list refreshes itself when packages are installed, removed or updated.
 */
class AppRepository private constructor(private val context: Context) {

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loadJob: Job? = null

    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Icons arrive after the list so the home screen paints immediately. */
    private val _icons = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val icons: StateFlow<Map<String, ImageBitmap>> = _icons.asStateFlow()

    private val activityInfos = ConcurrentHashMap<String, LauncherActivityInfo>()
    private val iconSizePx = (context.resources.displayMetrics.density * 56).toInt().coerceIn(96, 192)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) = refresh()
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = refresh()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = refresh()
        override fun onPackagesAvailable(p: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh()
        override fun onPackagesUnavailable(p: Array<out String>?, user: UserHandle?, replacing: Boolean) = refresh()
    }

    init {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        loadJob = scope.launch {
            try {
                load()
            } catch (t: Throwable) {
                Log.e(TAG, "App list load failed", t)
                _loaded.value = true
            }
        }
    }

    private suspend fun load() {
        val me = Process.myUserHandle()
        val profiles = runCatching { userManager.userProfiles }.getOrDefault(listOf(me))
        val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
        val ownPackage = context.packageName
        val entries = ArrayList<AppEntry>()
        val infos = HashMap<String, LauncherActivityInfo>()
        for (profile in profiles) {
            val list = runCatching { launcherApps.getActivityList(null, profile) }.getOrDefault(emptyList())
            val serial = userManager.getSerialNumberForUser(profile)
            for (info in list) {
                val cn = info.componentName
                // Hide the launcher's own home entry; keep Hero Weather, which lives in our package.
                if (cn.packageName == ownPackage && cn.className.endsWith(".MainActivity")) continue
                val key = "${cn.flattenToShortString()}#$serial"
                infos[key] = info
                entries += AppEntry(
                    key = key,
                    label = info.label?.toString()?.trim().orEmpty().ifEmpty { cn.packageName },
                    packageName = cn.packageName,
                    component = cn,
                    user = profile,
                    category = info.applicationInfo?.category ?: ApplicationInfo.CATEGORY_UNDEFINED,
                    isWorkProfile = profile != me,
                )
            }
        }
        entries.sortWith { a, b -> collator.compare(a.label, b.label) }
        activityInfos.clear()
        activityInfos.putAll(infos)
        _apps.value = entries
        _loaded.value = true

        // Icons: reuse cached ones, load the rest.
        val current = _icons.value
        val next = HashMap<String, ImageBitmap>(entries.size)
        for (e in entries) {
            val cached = current[e.key]
            if (cached != null) {
                next[e.key] = cached
                continue
            }
            val info = infos[e.key] ?: continue
            val bmp = runCatching {
                val d = info.getBadgedIcon(0)
                d.toBitmap(iconSizePx, iconSizePx).asImageBitmap()
            }.getOrNull() ?: continue
            next[e.key] = bmp
            // Publish in chunks so icons pop in progressively on a cold start.
            if (next.size % 24 == 0) _icons.value = HashMap(next)
        }
        _icons.value = next
    }

    fun find(key: String): AppEntry? = _apps.value.firstOrNull { it.key == key }

    fun launch(app: AppEntry, bounds: Rect? = null) {
        try {
            launcherApps.startMainActivity(app.component, app.user, bounds, null)
        } catch (t: Throwable) {
            Log.w(TAG, "Launch failed for ${app.key}", t)
            Toast.makeText(context, "Couldn't open ${app.label}", Toast.LENGTH_SHORT).show()
            refresh()
        }
    }

    fun openAppInfo(app: AppEntry) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    fun uninstall(app: AppEntry) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    companion object {
        private const val TAG = "AppRepository"

        @Volatile private var instance: AppRepository? = null

        fun get(context: Context): AppRepository =
            instance ?: synchronized(this) {
                instance ?: AppRepository(context.applicationContext).also { instance = it }
            }
    }
}
