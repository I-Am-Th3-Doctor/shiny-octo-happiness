package io.github.iamth3doctor.searchlight.widgets

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import io.github.iamth3doctor.searchlight.MainActivity
import io.github.iamth3doctor.searchlight.R

/**
 * An app asked to add one of its widgets to the home screen (requestPinAppWidget, "Add to home
 * screen"). Asks you, then binds it under our host and hands it to home, which places it in
 * the first free space that fits.
 */
class PinWidgetActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = getSystemService(LauncherApps::class.java).getPinItemRequest(intent)
        if (request == null || request.requestType != LauncherApps.PinItemRequest.REQUEST_TYPE_APPWIDGET || !request.isValid) {
            finish()
            return
        }
        val info = request.getAppWidgetProviderInfo(this) ?: run { finish(); return }
        AlertDialog.Builder(this)
            .setTitle(R.string.widget_pin_title)
            .setMessage(info.loadLabel(packageManager))
            .setPositiveButton(R.string.widget_pin_add) { _, _ ->
                val host = SearchlightWidgetHost(applicationContext, WidgetController.HOST_ID)
                val id = host.allocateAppWidgetId()
                // Accepting with our id makes the system bind the widget to it.
                if (request.accept(Bundle().apply { putInt(AppWidgetManager.EXTRA_APPWIDGET_ID, id) })) {
                    startActivity(
                        Intent(this, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_PLACE_WIDGET, id)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } else {
                    host.deleteAppWidgetId(id)
                }
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }
}
