package io.github.iamth3doctor.searchlight.widgets

import android.app.Activity
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
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.data.Prefs
import kotlin.math.roundToInt

/**
 * Real Android widgets on a free-placement grid above the home card.
 *
 * Positions are stored as grid cells ([GridSpec]); [WidgetGridView] turns them into pixels.
 * When the grid changes size (fold/unfold), stored positions are re-fitted for display only, so
 * unfolding again restores the original arrangement.
 *
 * Add flow (the same one the stock launcher uses):
 *   picker → allocate id → bind (asks you once: "Allow Searchlight to create widgets") →
 *   the widget's own configure screen, if it has one → first free space that fits its span.
 */
class WidgetController(
    private val activity: Activity,
    private val grid: WidgetGridView,
    private val prefs: Prefs,
    private val onEditModeChanged: (Boolean) -> Unit,
) : WidgetGridView.Listener {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = SearchlightWidgetHost(activity.applicationContext, HOST_ID)
    private val views = HashMap<Int, AppWidgetHostView>()
    private val reportedSizes = HashMap<Int, Pair<Int, Int>>()
    private val density = activity.resources.displayMetrics.density

    /** An id allocated for a widget that's still going through bind/configure. */
    var pendingId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    val editing get() = grid.editMode

    init {
        grid.listener = this
        grid.emptyHint = activity.getString(R.string.widgets_empty_hint)
        migrateFromStack()
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
        exitEditMode()
        try {
            host.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "stopListening failed", e)
        }
    }

    fun exitEditMode() = grid.setEditMode(false)

    /** Frees ids the system still has for us but that aren't on the grid (e.g. an add that was abandoned). */
    fun cleanUpOrphans() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val placed = prefs.widgetGrid.mapTo(HashSet()) { it.id }
        host.appWidgetIds.filter { it !in placed && it != pendingId }.forEach { host.deleteAppWidgetId(it) }
    }

    /** One time: the old single-column stack becomes full-width rows on the grid. */
    private fun migrateFromStack() {
        val legacy = prefs.takeLegacyWidgetStack() ?: return
        if (prefs.widgetGrid.isNotEmpty()) return
        prefs.widgetGrid = WidgetGrid.fromStack(legacy, grid.cols, grid.rows, cellHeightDp = 80f)
    }

    // ------------------------------------------------------------------ rendering

    fun render() {
        val stored = prefs.widgetGrid
        val alive = stored.filter { manager.getAppWidgetInfo(it.id) != null }
        if (alive.size != stored.size) {
            // The app behind a widget was uninstalled: take it off the grid.
            stored.filter { it !in alive }.forEach { gone ->
                host.deleteAppWidgetId(gone.id)
                views.remove(gone.id)
            }
            prefs.widgetGrid = alive
        }
        val fit = WidgetGrid.fitToGrid(alive, grid.cols, grid.rows)
        grid.setWidgets(fit.shown.mapNotNull { spec -> viewFor(spec.id)?.let { it to spec } })
    }

    private fun viewFor(id: Int): AppWidgetHostView? {
        views[id]?.let { return it }
        val info = manager.getAppWidgetInfo(id) ?: return null
        return host.createView(activity, id, info).also { v ->
            (v as? LongPressWidgetHostView)?.onLongPress = { grid.enterEditModeFromLongPress() }
            // Tell the widget its real size whenever its cell span (or the cell size) changes.
            v.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ -> reportSize(id, v, r - l, b - t) }
            views[id] = v
        }
    }

    private fun reportSize(id: Int, view: AppWidgetHostView, wPx: Int, hPx: Int) {
        if (wPx <= 0 || hPx <= 0) return
        val w = (wPx / density).roundToInt()
        val h = (hPx / density).roundToInt()
        if (reportedSizes[id] == w to h) return
        reportedSizes[id] = w to h
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(w.toFloat(), h.toFloat())))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(null, w, h, w, h)
        }
    }

    // ------------------------------------------------------------------ grid callbacks

    override fun onEditModeChanged(editing: Boolean) = onEditModeChanged.invoke(editing)

    override fun onGridSizeChanged(cols: Int, rows: Int) = render()

    /** A move or resize was dropped on free cells: that's now the stored position. */
    override fun onCommit(spec: GridSpec) {
        prefs.widgetGrid = WidgetGrid.upsert(prefs.widgetGrid, spec)
    }

    override fun onRemove(id: Int) {
        prefs.widgetGrid = WidgetGrid.remove(prefs.widgetGrid, id)
        host.deleteAppWidgetId(id)
        views.remove(id)
        reportedSizes.remove(id)
        render()
        Toast.makeText(activity, R.string.widget_removed, Toast.LENGTH_SHORT).show()
    }

    override fun resizable(id: Int): Pair<Boolean, Boolean> {
        val mode = manager.getAppWidgetInfo(id)?.resizeMode ?: return false to false
        return (mode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0) to (mode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0)
    }

    override fun minSpan(id: Int): Pair<Int, Int> {
        val info = manager.getAppWidgetInfo(id) ?: return 1 to 1
        val (canH, canV) = resizable(id)
        val wPx = if (canH && info.minResizeWidth > 0) info.minResizeWidth else info.minWidth
        val hPx = if (canV && info.minResizeHeight > 0) info.minResizeHeight else info.minHeight
        return WidgetGrid.spanFor(wPx / density, grid.cellWidthDp, grid.cols) to
            WidgetGrid.spanFor(hPx / density, grid.cellHeightDp, grid.rows)
    }

    // ------------------------------------------------------------------ add flow

    /** Opens the widget picker; the chosen widget lands in the first free space that fits it. */
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
        val (w, h) = defaultSpan(info)
        val (minW, minH) = minSpan(id)
        val spot = WidgetGrid.place(grid.placedSpecs(), id, w, h, minW, minH, grid.cols, grid.rows)
        if (spot == null) {
            host.deleteAppWidgetId(id)
            Toast.makeText(activity, R.string.widget_no_room, Toast.LENGTH_LONG).show()
            return
        }
        prefs.widgetGrid = WidgetGrid.upsert(prefs.widgetGrid, spot)
        render()
    }

    private fun abandon() {
        if (pendingId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(pendingId)
        pendingId = AppWidgetManager.INVALID_APPWIDGET_ID
    }

    /** The span a widget asks for: its declared target cells (Android 12+), else its minimum size. */
    private fun defaultSpan(info: AppWidgetProviderInfo): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && info.targetCellWidth > 0 && info.targetCellHeight > 0) {
            return info.targetCellWidth.coerceAtMost(grid.cols) to info.targetCellHeight.coerceAtMost(grid.rows)
        }
        return WidgetGrid.spanFor(info.minWidth / density, grid.cellWidthDp, grid.cols) to
            WidgetGrid.spanFor(info.minHeight / density, grid.cellHeightDp, grid.rows)
    }

    private inline fun <reified T : android.os.Parcelable> Intent.getParcelableExtraCompat(key: String): T? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
        else @Suppress("DEPRECATION") getParcelableExtra(key)

    companion object {
        private const val TAG = "Widgets"
        const val HOST_ID = 0x5EA2
        const val REQUEST_PICK = 101
        const val REQUEST_BIND = 102
        const val REQUEST_CONFIGURE = 103
    }
}
