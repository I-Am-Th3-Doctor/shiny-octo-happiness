package io.github.iamth3doctor.searchlight.widgets

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.util.SizeF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.data.Prefs
import kotlin.math.roundToInt

/**
 * Real Android widgets on the home screen: one column in a scrollable page above the home card.
 *
 * Add flow (the same one the stock launcher uses):
 *   picker → allocate id → bind (asks you once: "Allow Searchlight to create widgets") →
 *   the widget's own configure screen, if it has one → placed at the bottom of the page.
 * Long-press a widget for Resize / Move up / Move down / Remove.
 */
class WidgetController(
    private val activity: Activity,
    private val container: LinearLayout,
    private val prefs: Prefs,
) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = SearchlightWidgetHost(activity.applicationContext, HOST_ID)
    private val views = HashMap<Int, AppWidgetHostView>()
    private val reportedSizes = HashMap<Int, Pair<Int, Int>>()
    private val density = activity.resources.displayMetrics.density

    /** An id allocated for a widget that's still going through bind/configure. */
    var pendingId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    init {
        // Fold/unfold changes the column width: tell widgets their new size so they re-layout.
        container.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) reportSizes() }
    }

    fun start() {
        try {
            host.startListening()
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
        }
        // Widgets may have changed (or been uninstalled) while we weren't listening.
        render()
    }

    fun stop() {
        try {
            host.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening failed", e)
        }
    }

    /** Frees ids the system still has for us but that aren't on the page (e.g. an add that was abandoned). */
    fun cleanUpOrphans() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val placed = prefs.widgets.mapTo(HashSet()) { it.id }
        host.appWidgetIds.filter { it !in placed && it != pendingId }.forEach { host.deleteAppWidgetId(it) }
    }

    // ------------------------------------------------------------------ rendering

    fun render() {
        val specs = prefs.widgets
        val alive = specs.filter { manager.getAppWidgetInfo(it.id) != null }
        if (alive.size != specs.size) {
            // The app behind a widget was uninstalled: drop it from the page.
            specs.filter { it !in alive }.forEach { gone ->
                host.deleteAppWidgetId(gone.id)
                views.remove(gone.id)
            }
            prefs.widgets = alive
        }

        container.removeAllViews()
        for (spec in alive) {
            val info = manager.getAppWidgetInfo(spec.id) ?: continue
            val view = views.getOrPut(spec.id) {
                host.createView(activity, spec.id, info).also { v ->
                    (v as? LongPressWidgetHostView)?.onLongPress = { showWidgetMenu(spec.id, v) }
                }
            }
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(spec.heightDp)).apply {
                bottomMargin = dp(8)
            })
        }
        if (alive.isEmpty()) {
            container.addView(TextView(activity).apply {
                setText(R.string.widgets_empty_hint)
                setTextColor(activity.getColor(R.color.on_wallpaper_dim))
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, dp(24))
            })
        }
        container.post { reportSizes() }
    }

    private fun reportSizes() {
        for ((id, view) in views) {
            if (view.parent == null || view.width == 0) continue
            val w = (view.width / density).roundToInt()
            val h = (view.height / density).roundToInt()
            if (reportedSizes[id] == w to h) continue
            reportedSizes[id] = w to h
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                view.updateAppWidgetSize(Bundle(), listOf(SizeF(w.toFloat(), h.toFloat())))
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(null, w, h, w, h)
            }
        }
    }

    // ------------------------------------------------------------------ add flow

    fun beginAdd() {
        @Suppress("DEPRECATION")
        activity.startActivityForResult(Intent(activity, WidgetPickerActivity::class.java), REQUEST_PICK)
    }

    /** @return true if the result belonged to the widget flow. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        when (requestCode) {
            REQUEST_PICK -> {
                if (resultCode != Activity.RESULT_OK || data == null) return true
                val provider = data.getParcelableExtraCompat<ComponentName>(WidgetPickerActivity.EXTRA_PROVIDER) ?: return true
                val profile = data.getParcelableExtraCompat<UserHandle>(WidgetPickerActivity.EXTRA_PROFILE)
                bind(provider, profile)
            }
            REQUEST_BIND -> if (resultCode == Activity.RESULT_OK) configureOrPlace(pendingId) else abandon()
            REQUEST_CONFIGURE -> if (resultCode == Activity.RESULT_OK) place(pendingId) else abandon()
            else -> return false
        }
        return true
    }

    private fun bind(provider: ComponentName, profile: UserHandle?) {
        val id = host.allocateAppWidgetId()
        pendingId = id
        val bound = try {
            manager.bindAppWidgetIdIfAllowed(id, profile ?: android.os.Process.myUserHandle(), provider, null)
        } catch (e: Exception) {
            false
        }
        if (bound) {
            configureOrPlace(id)
        } else {
            // Android asks you once whether Searchlight may host widgets ("Always allow").
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
            if (profile != null) intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, profile)
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, REQUEST_BIND)
        }
    }

    private fun configureOrPlace(id: Int) {
        val info = manager.getAppWidgetInfo(id) ?: return abandon()
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure == null || optional) {
            place(id)
            return
        }
        try {
            host.startAppWidgetConfigureActivityForResult(activity, id, 0, REQUEST_CONFIGURE, null)
        } catch (e: Exception) {
            Log.w(TAG, "Configure screen failed; placing anyway", e)
            place(id)
        }
    }

    private fun place(id: Int) {
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
        val info = manager.getAppWidgetInfo(id) ?: return
        prefs.widgets = WidgetLayout.add(prefs.widgets, WidgetSpec(id, defaultHeightDp(info)))
        render()
    }

    private fun abandon() {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private fun defaultHeightDp(info: AppWidgetProviderInfo): Int {
        val fromMin = (info.minHeight / density).roundToInt()
        val fromCells = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellHeight > 0) info.targetCellHeight * CELL_DP else 0
        return maxOf(fromMin, fromCells, MIN_HEIGHT_DP).coerceAtMost(MAX_HEIGHT_DP)
    }

    private fun minHeightDp(info: AppWidgetProviderInfo): Int {
        val resizable = info.resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0
        val px = if (resizable && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return maxOf((px / density).roundToInt(), MIN_HEIGHT_DP)
    }

    // ------------------------------------------------------------------ edit

    private fun showWidgetMenu(id: Int, anchor: View) {
        val list = prefs.widgets
        val index = list.indexOfFirst { it.id == id }
        PopupMenu(activity, anchor, Gravity.CENTER).apply {
            menu.add(0, 1, 0, R.string.widget_resize)
            if (index > 0) menu.add(0, 2, 1, R.string.widget_move_up)
            if (index in 0 until list.lastIndex) menu.add(0, 3, 2, R.string.widget_move_down)
            menu.add(0, 4, 3, R.string.widget_remove)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> showResize(id)
                    2 -> { prefs.widgets = WidgetLayout.move(prefs.widgets, id, -1); render() }
                    3 -> { prefs.widgets = WidgetLayout.move(prefs.widgets, id, +1); render() }
                    4 -> remove(id)
                }
                true
            }
            show()
        }
    }

    private fun remove(id: Int) {
        prefs.widgets = WidgetLayout.remove(prefs.widgets, id)
        host.deleteAppWidgetId(id)
        views.remove(id)
        reportedSizes.remove(id)
        render()
        Toast.makeText(activity, R.string.widget_removed, Toast.LENGTH_SHORT).show()
    }

    /** Height slider with live preview; OK saves, Cancel puts it back. Width always fills the column. */
    private fun showResize(id: Int) {
        val spec = prefs.widgets.firstOrNull { it.id == id } ?: return
        val info = manager.getAppWidgetInfo(id) ?: return
        val view = views[id] ?: return
        val minDp = minHeightDp(info)
        val label = TextView(activity).apply { textSize = 15f }
        fun apply(hDp: Int) {
            view.layoutParams = view.layoutParams.apply { height = dp(hDp) }
            label.text = activity.getString(R.string.widget_height, hDp)
        }
        val seek = SeekBar(activity).apply {
            max = (MAX_HEIGHT_DP - minDp) / STEP_DP
            progress = ((spec.heightDp - minDp) / STEP_DP).coerceIn(0, max)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = apply(minDp + value * STEP_DP)
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }
        apply(spec.heightDp.coerceAtLeast(minDp))
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label)
            addView(seek)
        }
        AlertDialog.Builder(activity)
            .setTitle(R.string.widget_resize)
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                prefs.widgets = WidgetLayout.resize(prefs.widgets, id, minDp + seek.progress * STEP_DP, minDp, MAX_HEIGHT_DP)
                render()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> render() }
            .setOnCancelListener { render() }
            .show()
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(key)

    companion object {
        private const val TAG = "Widgets"
        const val HOST_ID = 0x5EA2
        const val REQUEST_PICK = 101
        const val REQUEST_BIND = 102
        const val REQUEST_CONFIGURE = 103
        private const val CELL_DP = 80
        private const val MIN_HEIGHT_DP = 48
        private const val MAX_HEIGHT_DP = 720
        private const val STEP_DP = 8
    }
}
