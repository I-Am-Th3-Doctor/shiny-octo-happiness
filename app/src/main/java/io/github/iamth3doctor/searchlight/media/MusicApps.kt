package io.github.iamth3doctor.searchlight.media

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.KeyEvent

class MusicApp(val packageName: String, val label: String)

/**
 * Finds installed media apps from what they declare, not from a hardcoded list: anything that
 * exposes a MediaBrowserService (Spotify, YouTube Music, Pocket Casts…), handles media buttons,
 * or is categorized as a music app. Only apps you can actually open are offered.
 */
object MusicApps {

    private const val MEDIA_BROWSER = "android.media.browse.MediaBrowserService"

    fun installed(context: Context): List<MusicApp> {
        val pm = context.packageManager
        val packages = LinkedHashSet<String>()
        pm.queryIntentServices(Intent(MEDIA_BROWSER), 0).forEach { packages += it.serviceInfo.packageName }
        pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0).forEach { packages += it.activityInfo.packageName }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MUSIC), 0)
            .forEach { packages += it.activityInfo.packageName }
        return packages
            .filter { it != context.packageName && pm.getLaunchIntentForPackage(it) != null }
            .map { MusicApp(it, label(pm, it)) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    fun isInstalled(context: Context, pkg: String) = context.packageManager.getLaunchIntentForPackage(pkg) != null

    fun label(context: Context, pkg: String) = label(context.packageManager, pkg)

    private fun label(pm: PackageManager, pkg: String): String = try {
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    /**
     * Sends a media key straight to [pkg] (e.g. PLAY when it has no active session yet).
     * Most players start up and resume playback from this.
     */
    fun sendMediaKey(context: Context, pkg: String, keyCode: Int) {
        val receiver = context.packageManager
            .queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg), 0)
            .firstOrNull()?.activityInfo?.let { ComponentName(it.packageName, it.name) }
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON).putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, keyCode))
            if (receiver != null) intent.component = receiver else intent.setPackage(pkg)
            context.sendBroadcast(intent)
        }
    }
}
