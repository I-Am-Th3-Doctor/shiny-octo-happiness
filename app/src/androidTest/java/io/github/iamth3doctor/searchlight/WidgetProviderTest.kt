package io.github.iamth3doctor.searchlight

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.res.Resources
import android.os.Build
import android.os.Bundle
import android.os.UserManager
import android.util.SizeF
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.widgets.SearchlightWidgetHost
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Binds every widget provider installed on the device (all profiles), renders it at its default
 * size the way the home screen does, and writes one line per provider to widgets-<prefix>.txt
 * next to the screenshots: OK, or what went wrong. A report, not a gate: which widgets exist
 * depends on the device image.
 */
@RunWith(AndroidJUnit4::class)
class WidgetProviderTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"

    @Test fun everyProviderBindsAndRenders() {
        val manager = AppWidgetManager.getInstance(context)
        val profiles = context.getSystemService(UserManager::class.java).userProfiles
        // What the user grants once through the system "Allow Searchlight to create widgets" dialog.
        for (p in profiles) {
            instrumentation.uiAutomation.executeShellCommand(
                "appwidget grantbind --package ${context.packageName} --user ${p.hashCode()}"
            ).close()
        }
        // The report goes next to the screenshots in shared storage.
        instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} MANAGE_EXTERNAL_STORAGE allow").close()
        Thread.sleep(500)

        val host = SearchlightWidgetHost(context, TEST_HOST_ID)
        host.deleteHost()
        instrumentation.runOnMainSync { host.startListening() }
        val inflateError = Resources.getSystem().getIdentifier("gadget_host_error_inflating", "string", "android")
            .takeIf { it != 0 }?.let { Resources.getSystem().getString(it) }
        val density = context.resources.displayMetrics.density
        val lines = ArrayList<String>()

        for (profile in profiles) for (info in manager.getInstalledProvidersForProfile(profile)) {
            val name = "${info.loadLabel(context.packageManager)} (${info.provider.flattenToShortString()})"
            val id = host.allocateAppWidgetId()
            try {
                if (!manager.bindAppWidgetIdIfAllowed(id, profile, info.provider, null)) {
                    lines += "FAIL  $name: bind refused"
                    continue
                }
                val w = (info.minWidth.coerceAtLeast(1) / density)
                val h = (info.minHeight.coerceAtLeast(1) / density)
                manager.updateAppWidgetOptions(id, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w.toInt())
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w.toInt())
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h.toInt())
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h.toInt())
                    if (Build.VERSION.SDK_INT >= 31) {
                        putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(w, h)))
                    }
                })
                var view: AppWidgetHostView? = null
                var error: Throwable? = null
                instrumentation.runOnMainSync {
                    try {
                        view = host.createView(context, id, manager.getAppWidgetInfo(id)).also {
                            it.measure(
                                View.MeasureSpec.makeMeasureSpec(info.minWidth.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(info.minHeight.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                            )
                            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
                        }
                    } catch (t: Throwable) { error = t }
                }
                // Give the provider time to answer the bind with its first update.
                Thread.sleep(1500)
                instrumentation.waitForIdleSync()
                var shown = ""
                instrumentation.runOnMainSync { shown = view?.let { texts(it) }.orEmpty() }
                lines += when {
                    error != null -> "FAIL  $name: ${error!!.javaClass.simpleName}: ${error!!.message}"
                    inflateError != null && shown.contains(inflateError) -> "FAIL  $name: \"$inflateError\" (its RemoteViews didn't inflate)"
                    info.configure != null -> "OK    $name (has a configure screen; bound without it here)"
                    else -> "OK    $name"
                }
            } catch (t: Throwable) {
                lines += "FAIL  $name: ${t.javaClass.simpleName}: ${t.message}"
            } finally {
                host.deleteAppWidgetId(id)
            }
        }
        instrumentation.runOnMainSync { host.stopListening() }
        host.deleteHost()

        val dir = File("/sdcard/Download/searchlight-shots").apply { mkdirs() }
        File(dir, "widgets-$prefix.txt").writeText(
            "Widget providers on ${Build.MODEL} (API ${Build.VERSION.SDK_INT}), $prefix: ${lines.size}\n" +
                lines.sorted().joinToString("\n") + "\n"
        )
        android.util.Log.i("WidgetProviderTest", lines.joinToString("\n"))
    }

    private fun texts(v: View): String =
        if (v is TextView) v.text.toString() + "\n"
        else if (v is ViewGroup) (0 until v.childCount).joinToString("") { texts(v.getChildAt(it)) }
        else ""

    private companion object {
        /** Our own host, so the test never touches the home screen's widgets. */
        const val TEST_HOST_ID = 0x7E57
    }
}
