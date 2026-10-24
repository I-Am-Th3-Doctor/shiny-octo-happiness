package io.github.iamth3doctor.searchlight.gestures

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Searchlight's accessibility service. It reads nothing on screen and listens to no events: it
 * exists only so the home gestures can open the notification shade and quick settings and lock
 * the screen, which apps can do only through [performGlobalAction].
 */
class GestureService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    companion object {
        @Volatile private var instance: GestureService? = null

        /** Turned on in Accessibility settings (it may take a moment to connect after that). */
        fun isEnabled(c: Context): Boolean {
            val me = ComponentName(c, GestureService::class.java)
            val enabled = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }

        /** Connected and ready to act (turning it on takes a moment). */
        val isConnected: Boolean get() = instance != null

        /** Runs [action] (GLOBAL_ACTION_*). False when the service isn't connected or the system refused. */
        fun perform(action: Int): Boolean = instance?.performGlobalAction(action) == true
    }
}
