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
import io.github.iamth3doctor.searchlight.ui.Posture
import kotlin.math.roundToInt

/**
 * Real Android widgets on a free-placement grid above the home card.
 *
 * Positions are stored as grid cells ([GridSpec]); [WidgetGridView] turns them into pixels.
 * Folded and unfolded (7 columns) each have their own stored layout: arranging widgets in one
 * never moves them in the other. A widget that's only been placed in one gets the first free
 * spot in the other the first time that one is shown. If the grid changes size within a
 * posture, stored positions are re-fitted for display only.
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

    /** Which layout is showing; set on start and whenever the device folds or unfolds. */
    var posture: Posture = Posture.FOLDED
        set(value) {
            if (field == value) return
            field = value
            exitEditMode()
            grid.unfolded = value == Posture.UNFOLDED // re-measures, then renders that layout
        }

    private val unfolded get() = posture == Posture.UNFOLDED

    /** This posture's stored layout. */
    private var layout: List<GridSpec>
        get() = if (unfolded) prefs.widgetGridUnfolded else prefs.widgetGrid
        set(value) {
            if (unfolded) prefs.widgetGridUnfolded = value else prefs.widgetGrid = value
        }

    private val otherLayout get() = if (unfolded) prefs.widgetGrid else prefs.widgetGridUnfolded

    init {
        grid.listener = this
        host.onProvidersChanged = ::refreshProviders
        migrateFromStack()
    }

    /**
     * An app with widgets was installed, updated or removed: rebuild the views whose provider
     * changed (or vanished) instead of leaving them blank or stale; render() drops widgets whose
     * app is gone.
     */
    private fun refreshProviders() {
        for (id in views.keys.toList()) {
            val now = manager.getAppWidgetInfo(id)
            val shown = views[id]?.appWidgetInfo
            if (now == null || shown == null || now.provider != shown.provider || now.initialLayout != shown.initialLayout) {
                views.remove(id)
                reportedSizes.remove(id)
            }
        }
        render()
    }

    /**
     * A widget an app asked to pin ("Add to home screen"), already bound: place it like a new
     * one, once the grid has been measured (on a cold start it hasn't yet).
     */
    fun placePinned(id: Int) {
        if (grid.measuredUnfolded == null) pendingPin = id else place(id)
    }

    private var pendingPin = AppWidgetManager.INVALID_APPWIDGET_ID

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
        val placed = (prefs.widgetGrid + prefs.widgetGridUnfolded).mapTo(HashSet()) { it.id }
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
        // The app behind a widget was uninstalled: take it off both layouts.
        val gone = (prefs.widgetGrid + prefs.widgetGridUnfolded).map { it.id }.distinct()
            .filter { manager.getAppWidgetInfo(it) == null }
        if (gone.isNotEmpty()) {
            gone.forEach { id ->
                host.deleteAppWidgetId(id)
                views.remove(id)
            }
            prefs.widgetGrid = prefs.widgetGrid.filter { it.id !in gone }
            prefs.widgetGridUnfolded = prefs.widgetGridUnfolded.filter { it.id !in gone }
        }
        var current = layout
        // Widgets so far only arranged in the other posture get a spot here: only once the
        // grid has been measured for this posture, so they're placed on the right grid.
        if (grid.measuredUnfolded == unfolded) {
            val arranged = WidgetGrid.withMissing(current, otherLayout, grid.cols, grid.rows)
            if (arranged.size != current.size) {
                layout = arranged
                current = arranged
            }
        }
        val fit = WidgetGrid.fitToGrid(current, grid.cols, grid.rows)
        // Tell new widgets their size before their first layout, so they inflate the right
        // layout for it from the start (layout then reports the exact size).
        for (spec in fit.shown) if (spec.id !in reportedSizes) reportCells(spec)
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

    private fun reportCells(spec: GridSpec) {
        val w = (grid.cellWidthDp * spec.colSpan).roundToInt()
        val h = (grid.cellHeightDp * spec.rowSpan).roundToInt()
        if (w <= 0 || h <= 0) return
        reportedSizes[spec.id] = w to h
        val options = Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, h)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(w.toFloat(), h.toFloat())))
            }
        }
        try {
            manager.updateAppWidgetOptions(spec.id, options)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't send size to widget ${spec.id}", e)
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

    override fun onGridSizeChanged(cols: Int, rows: Int) {
        if (pendingPin != AppWidgetManager.INVALID_APPWIDGET_ID) {
            val id = pendingPin
            pendingPin = AppWidgetManager.INVALID_APPWIDGET_ID
            place(id) // renders
        } else {
            render()
        }
    }

    /** A move or resize was dropped on free cells: that's now the stored position. */
    override fun onCommit(spec: GridSpec) {
        layout = WidgetGrid.upsert(layout, spec)
    }

    override fun onRemove(id: Int) {
        prefs.widgetGrid = WidgetGrid.remove(prefs.widgetGrid, id)
        prefs.widgetGridUnfolded = WidgetGrid.remove(prefs.widgetGridUnfolded, id)
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

    /**
     * Opens the widget picker (only [pkg]'s widgets, in [user]'s profile, when given); the chosen
     * widget lands in the first free space that fits it.
     */
    fun beginAdd(pkg: String? = null, user: UserHandle? = null) {
        val intent = Intent(activity, WidgetPickerActivity::class.java)
        if (pkg != null) {
            intent.putExtra(WidgetPickerActivity.EXTRA_FILTER_PACKAGE, pkg).putExtra(WidgetPickerActivity.EXTRA_FILTER_USER, user)
        }
        @Suppress("DEPRECATION")
        activity.startActivityForResult(intent, REQUEST_PICK)
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
        layout = WidgetGrid.upsert(layout, spot)
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
