package io.github.iamth3doctor.searchlight.ui

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import io.github.iamth3doctor.searchlight.gestures.GestureService
import io.github.iamth3doctor.searchlight.media.MediaListenerService

/** Checks and settings-screen shortcuts for the special (non-runtime) permissions. */
object Permissions {

    private fun Context.start(intent: Intent, fallback: Intent? = null) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            fallback?.let { runCatching { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        }
    }

    private fun packageUri(c: Context) = Uri.fromParts("package", c.packageName, null)

    fun openAppDetails(c: Context) = c.start(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(c)))

    fun hasMediaAccess(c: Context) = NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)

    fun openNotificationListenerSettings(c: Context) {
        val generic = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(c, MediaListenerService::class.java).flattenToString(),
                )
            c.start(detail, generic)
        } else {
            c.start(generic)
        }
    }

    fun hasDndAccess(c: Context) = c.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun openDndAccessSettings(c: Context) = c.start(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))

    /** Usage access (automatic favorites): our own page where the device has one, else the list. */
    fun openUsageAccessSettings(c: Context) =
        c.start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, packageUri(c)), Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    /**
     * Accessibility (home gestures): straight to Searchlight's own page where the device allows
     * it, else the Accessibility list.
     */
    fun openAccessibilitySettings(c: Context) = c.start(
        Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(c, GestureService::class.java).flattenToString()),
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
    )

    fun hasWriteSettings(c: Context) = Settings.System.canWrite(c)

    fun openWriteSettings(c: Context) =
        c.start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri(c)), Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS))

    fun openAllFilesSettings(c: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            c.start(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, packageUri(c)),
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION),
            )
        } else {
            openAppDetails(c)
        }
    }
}
