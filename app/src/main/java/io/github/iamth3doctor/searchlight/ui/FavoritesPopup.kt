package io.github.iamth3doctor.searchlight.ui

import android.app.Activity
import io.github.iamth3doctor.searchlight.apps.Badges
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.data.Prefs
import io.github.iamth3doctor.searchlight.databinding.ItemGridCellBinding
import io.github.iamth3doctor.searchlight.databinding.PopupFavoritesBinding

/**
 * The favorites shown when you long-press the magnifier: one row of six, as wide as the home
 * card, with icons sized so all six fit (folded or unfolded). From a home-screen gesture set to
 * the top six, the same six instead pop up as a small 3×2 box where the gesture landed ([showAt]).
 *
 * Hold, slide, go: the finger that held the magnifier can slide on along the row without
 * lifting ([slideMove]); the slot under it grows and lights up, and lifting there launches it
 * ([slideEnd]). Lifting before reaching the row leaves it open for tapping, as before; sliding
 * off it and lifting launches nothing and closes it.
 *
 * Not focusable on purpose: the keyboard stays up, so dismissing the grid (tap outside, or just
 * start typing) drops you straight back into search.
 */
class FavoritesPopup(
    private val activity: Activity,
    private val repo: AppRepository,
    private val prefs: Prefs,
    private val onLaunch: (AppEntry, View) -> Unit,
    private val onLongPress: (AppEntry, View) -> Unit,
) {
    private var window: PopupWindow? = null
    private var card: View? = null
    private val slide = SlideToLaunch()
    private var apps: List<AppEntry?> = emptyList()

    val isShowing get() = window?.isShowing == true

    /** The open row's icons and their apps, to update their unread counts in place. */
    private val badged = ArrayList<Pair<BadgedImageView, String>>()

    fun refreshBadges() = badged.forEach { (icon, pkg) -> icon.badgeCount = Badges.countFor(pkg) }
    /** The open row's view (its background is the glass). */
    val contentView: View? get() = window?.contentView

    /** The open row's six cells, in order (for tests). */
    internal var cells: List<View> = emptyList()
        private set

    /** The slot the sliding finger is on (for tests). */
    internal val slideSelection: Int? get() = slide.selected

    /** Where the open panel is: the row above the home card, or a 3×2 box at a tap on the home screen. */
    private sealed interface Place {
        data class Above(val card: View) : Place
        data class At(val root: View, val x: Float, val y: Float) : Place
    }
    private var place: Place? = null

    /** The row of six just above [above] (the home card): holding the magnifier. */
    fun show(above: View) = open(Place.Above(above))

    /**
     * A compact 3×2 box of the same six, in the same order, at the screen point ([x], [y]) where
     * the gesture that opens the top six landed: centred on it, below it near the top, above it
     * near the bottom, and always wholly inside [root]'s safe area (clear of the status bar,
     * navigation bar and cutouts), measured now, so folded or unfolded alike.
     */
    fun showAt(root: View, x: Float, y: Float) = open(Place.At(root, x, y))

    /** The open panel's box on screen, for tests. */
    internal fun boxOnScreen(): android.graphics.Rect? {
        val c = card ?: return null
        if (!isShowing) return null
        val loc = IntArray(2).also { c.getLocationOnScreen(it) }
        return android.graphics.Rect(loc[0], loc[1], loc[0] + c.width, loc[1] + c.height)
    }

    private fun open(where: Place) {
        dismiss()
        val above = (where as? Place.Above)?.card
        val at = where as? Place.At
        val b = PopupFavoritesBinding.inflate(LayoutInflater.from(activity))
        // The predicted slot's halo reaches a little past its cell: neither the row nor the card clips it.
        b.row.clipChildren = false
        b.root.clipChildren = false
        val favorites = homeFavorites(prefs, repo)
        val emptyHint = if (prefs.autoFavoritesActive) R.string.favorites_auto_empty_hint else R.string.favorites_empty_hint
        val inflater = LayoutInflater.from(activity)
        val iconPx = if (above != null) iconSize(above.width - b.root.paddingLeft - b.root.paddingRight)
            else activity.px(R.dimen.size_grid_icon)
        // At a tap: two rows of three. Same cells, same order, left to right then down.
        val gridRows = if (at != null) {
            b.row.orientation = LinearLayout.VERTICAL
            List(2) {
                LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                    .also { r -> b.row.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
            }
        } else null
        val shown = ArrayList<View>()
        badged.clear()

        for (i in 0 until Prefs.MAX_FAVORITES) {
            val cell = ItemGridCellBinding.inflate(inflater, b.row, false)
            cell.icon.layoutParams = cell.icon.layoutParams.apply { width = iconPx; height = iconPx }
            val fav = favorites.getOrNull(i)
            if (fav != null) {
                val entry = fav.entry
                cell.label.text = entry.label
                cell.icon.setImageBitmap(repo.icon(entry) { cell.icon.setImageBitmap(it) })
                cell.icon.badgeCount = Badges.countFor(entry.packageName)
                badged += cell.icon to entry.packageName
                // The predicted slot changes with what you use: a soft gold halo says so.
                if (fav.predicted) PredictedGlow.apply(cell.root, cell.icon)
                cell.root.contentDescription =
                    if (fav.predicted) activity.getString(R.string.favorite_predicted, entry.label) else entry.label
                cell.root.setOnClickListener { v ->
                    dismiss()
                    onLaunch(entry, v)
                }
                cell.root.setOnLongClickListener { v ->
                    onLongPress(entry, v)
                    true
                }
            } else {
                cell.icon.setImageResource(R.drawable.ic_add)
                cell.icon.setBackgroundResource(R.drawable.bg_empty_slot)
                cell.icon.alpha = 0.6f
                cell.label.text = ""
                cell.root.setOnClickListener {
                    Toast.makeText(activity, emptyHint, Toast.LENGTH_SHORT).show()
                }
            }
            (gridRows?.get(i / GRID_COLUMNS) ?: b.row).addView(cell.root)
            shown += cell.root
        }
        b.hint.setText(emptyHint)
        b.hint.visibility = if (favorites.all { it == null }) View.VISIBLE else View.GONE

        // The raised surface as glass: its own window, so no wallpaper slice under it, but the
        // glass setting scales it with every other surface, live.
        b.root.background = FrostedDrawable(
            b.root, activity.resources.getDimension(R.dimen.radius_panel),
            activity.getColor(R.color.surface_raised), activity.getColor(R.color.surface_raised), wallpaper = false,
        )
        val safe = at?.let { safeArea(it.root) }
        val width = if (above != null) above.width else {
            // Three cells across, each the icon plus room for its name; never wider than the screen allows.
            val cellW = iconPx + 2 * activity.px(R.dimen.space_m)
            minOf(GRID_COLUMNS * cellW + b.root.paddingLeft + b.root.paddingRight, safe!!.width() - 2 * activity.px(R.dimen.space_menu_margin))
        }
        val popup = PopupWindow(b.root, width, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.isOutsideTouchable = true
        popup.elevation = 12f
        popup.animationStyle = android.R.style.Animation_Dialog

        b.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val gap = activity.px(R.dimen.space_s)
        if (above != null) {
            // Sit just above the anchor (the home card), same width and left edge. Relative to the
            // anchor itself, so system-bar insets can't shift it onto the card.
            popup.showAsDropDown(above, 0, -(above.height + b.root.measuredHeight + gap), Gravity.START)
        } else {
            val root = at!!.root
            val origin = IntArray(2).also { root.getLocationOnScreen(it) }
            val s = safe!!
            val (x, y) = TapPlacement.place(
                at.x - origin[0], at.y - origin[1], width, b.root.measuredHeight,
                s.left, s.top, s.right, s.bottom, activity.px(R.dimen.space_menu_margin), activity.px(R.dimen.space_m),
            )
            popup.showAtLocation(root, Gravity.TOP or Gravity.START, x, y)
        }
        window = popup
        place = where
        cells = shown
        card = b.root
        apps = (0 until Prefs.MAX_FAVORITES).map { favorites.getOrNull(it)?.entry }
        slide.release(null, hasApp = false) // a new row: nothing selected, nothing entered
    }

    /** Redraws the open popup in place, e.g. when the automatic ranking changes under it. */
    fun rerenderIfShowing() {
        val p = place
        if (isShowing && p != null) open(p)
    }

    /**
     * The part of [root]'s window the box may use, in window coordinates: inside the status bar,
     * navigation bar, display cutout and keyboard. Read when it opens, so it's this display's.
     */
    private fun safeArea(root: View): android.graphics.Rect {
        val insets = root.rootWindowInsets?.getInsets(
            android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout() or
                android.view.WindowInsets.Type.ime()
        )
        return android.graphics.Rect(
            insets?.left ?: 0, insets?.top ?: 0,
            root.width - (insets?.right ?: 0), root.height - (insets?.bottom ?: 0),
        )
    }

    /** Largest icon (up to the usual size) that lets six cells fit across [rowWidthPx]. */
    private fun iconSize(rowWidthPx: Int): Int {
        val cellPadding = 2 * activity.px(R.dimen.space_xs)
        return minOf(activity.px(R.dimen.size_grid_icon), rowWidthPx / Prefs.MAX_FAVORITES - cellPadding)
    }

    /** The held finger moved to ([x], [y]) on screen. */
    fun slideMove(x: Float, y: Float) {
        if (!isShowing) return
        val slot = slotAt(x, y)
        if (!slide.move(slot, slot != null && apps.getOrNull(slot) != null)) return
        cells.forEachIndexed { i, cell -> highlight(cell, i == slide.selected) }
        slide.selected?.let { cells[it].performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
    }

    /** The held finger lifted at ([x], [y]) on screen, or the touch was [cancelled]. */
    fun slideEnd(x: Float, y: Float, cancelled: Boolean) {
        if (!isShowing) return
        val slot = slotAt(x, y)
        val outcome = slide.release(slot, slot != null && apps.getOrNull(slot) != null, cancelled)
        cells.forEach { highlight(it, false) }
        when (outcome) {
            SlideToLaunch.Outcome.StayOpen -> Unit // open for tapping, as before
            SlideToLaunch.Outcome.Dismiss -> dismiss()
            is SlideToLaunch.Outcome.Launch -> {
                val entry = apps[outcome.slot] ?: return
                val cell = cells[outcome.slot]
                dismiss()
                onLaunch(entry, cell)
            }
        }
    }

    /** Which slot is under the screen point: the card's height, each cell's width. */
    private fun slotAt(x: Float, y: Float): Int? {
        val c = card ?: return null
        if (cells.isEmpty()) return null
        val loc = IntArray(2)
        c.getLocationOnScreen(loc)
        val top = loc[1]
        val bottom = top + c.height
        val lefts = IntArray(cells.size)
        val rights = IntArray(cells.size)
        cells.forEachIndexed { i, cell ->
            cell.getLocationOnScreen(loc)
            lefts[i] = loc[0]
            rights[i] = loc[0] + cell.width
        }
        return SlideToLaunch.slotAt(x, y, top, bottom, lefts, rights)
    }

    /** The slot under the sliding finger: its icon a size up and the pressed highlight. */
    private fun highlight(cell: View, on: Boolean) {
        cell.isPressed = on
        val icon = cell.findViewById<View>(R.id.icon) ?: return
        val scale = if (on) SLIDE_SCALE else 1f
        if (icon.scaleX == scale) return
        icon.animate().scaleX(scale).scaleY(scale).setDuration(SLIDE_ANIM_MS)
            .setUpdateListener { cell.invalidate() } // the halo follows the icon's size
            .start()
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }

    private companion object {
        const val SLIDE_SCALE = 1.25f
        const val GRID_COLUMNS = 3
        const val SLIDE_ANIM_MS = 90L
    }
}
