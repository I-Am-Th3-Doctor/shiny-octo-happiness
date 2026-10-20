package io.github.iamth3doctor.searchlight.media

import android.service.notification.NotificationListenerService

/**
 * Intentionally empty. Android only lets an app see other apps' media sessions (for the Music
 * card) if it has an enabled notification listener. We never read or store notifications.
 */
class MediaListenerService : NotificationListenerService()
