package io.github.iamth3doctor.searchlight.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import io.github.iamth3doctor.searchlight.apps.BadgeCounts
import io.github.iamth3doctor.searchlight.apps.Badges

/**
 * Android only lets an app see other apps' media sessions (for the Music card) if it has an
 * enabled notification listener: this is it. It also keeps the unread badges ([Badges]): how
 * many notifications each app has posted. Only package names and counts are looked at; no
 * notification's title, text or contents are ever read or stored.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() = recount()

    override fun onListenerDisconnected() = Badges.update(emptyMap())

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    private fun recount() {
        val active = try { activeNotifications } catch (e: Exception) { null } ?: return
        Badges.update(BadgeCounts.count(active.map { sbn ->
            BadgeCounts.Posted(
                sbn.packageName,
                ongoing = sbn.isOngoing || sbn.notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
                groupSummary = sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            )
        }))
    }
}
