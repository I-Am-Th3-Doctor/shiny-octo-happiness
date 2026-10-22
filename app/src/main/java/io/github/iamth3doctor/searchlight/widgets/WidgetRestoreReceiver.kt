package io.github.iamth3doctor.searchlight.widgets

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.iamth3doctor.searchlight.searchlight

/**
 * After a backup is restored, Android re-creates our widgets under new ids and tells us the
 * old → new mapping; both stored layouts (folded, unfolded) are rewritten to the new ids.
 */
class WidgetRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AppWidgetManager.ACTION_APPWIDGET_HOST_RESTORED) return
        if (intent.getIntExtra(AppWidgetManager.EXTRA_HOST_ID, -1) != WidgetController.HOST_ID) return
        val old = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_OLD_IDS) ?: return
        val new = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS) ?: return
        val prefs = context.searchlight.prefs
        prefs.widgetGrid = WidgetGrid.remapIds(prefs.widgetGrid, old, new)
        prefs.widgetGridUnfolded = WidgetGrid.remapIds(prefs.widgetGridUnfolded, old, new)
    }
}
