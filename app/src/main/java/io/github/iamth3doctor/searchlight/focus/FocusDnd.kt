package io.github.iamth3doctor.searchlight.focus

import android.app.NotificationManager
import android.content.Context
import io.github.iamth3doctor.searchlight.data.Prefs

/**
 * Focus's Do Not Disturb (see [FocusRules.dndEnter]): on with Focus, back to exactly what it was
 * when Focus ends, by any route. Only the interruption filter is touched, never the person's DND
 * rules, so their exceptions (starred contacts, repeat callers, alarms) still apply; an ongoing
 * call is never interrupted (DND only affects what comes in next). Needs notification policy
 * access; without it, it does nothing.
 */
class FocusDnd(private val context: Context, private val prefs: Prefs) {

    private val nm get() = context.getSystemService(NotificationManager::class.java)

    fun hasAccess(): Boolean = nm.isNotificationPolicyAccessGranted

    /** Brings DND in line with Focus: call on every Focus change, and when access may have been granted. */
    fun sync() {
        val access = hasAccess()
        val d = if (prefs.focusMode) {
            FocusRules.dndEnter(nm.currentInterruptionFilter, prefs.dndBeforeFocus, access,
                NotificationManager.INTERRUPTION_FILTER_PRIORITY, NotificationManager.INTERRUPTION_FILTER_ALL)
        } else {
            FocusRules.dndExit(prefs.dndBeforeFocus, access)
        }
        d.set?.let { runCatching { nm.setInterruptionFilter(it) } }
        prefs.dndBeforeFocus = d.saved
    }
}
