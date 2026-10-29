package io.github.iamth3doctor.searchlight.ui

import android.app.Activity
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
 * card, with icons sized so all six fit (folded or unfolded).
 *
 * Not focusable on purpose: the keyboard stays up, so dismissing the grid (tap outside, or just
 * start typing) drops you straight back into search.
 */
class FavoritesPopup(
    private val activity: Activity,
    private val repo: AppRepository,
    private val prefs: Prefs,
    private val onLaunch: (AppEntry, View) -> Unit,
    /** The last flag: the press was on the predicted (6th) slot, so it can be pinned or unpinned. */
    private val onLongPress: (AppEntry, View, Boolean) -> Unit,
) {
    private var window: PopupWindow? = null
    private var anchor: View? = null

    val isShowing get() = window?.isShowing == true

    /** The open row's six cells, in order (for tests). */
    internal var cells: List<View> = emptyList()
        private set

    fun show(above: View) {
        dismiss()
        val b = PopupFavoritesBinding.inflate(LayoutInflater.from(activity))
        val favorites = homeFavorites(prefs, repo)
        val emptyHint = if (prefs.autoFavoritesActive) R.string.favorites_auto_empty_hint else R.string.favorites_empty_hint
        val inflater = LayoutInflater.from(activity)
        val iconPx = iconSize(above.width - b.root.paddingLeft - b.root.paddingRight)
        val shown = ArrayList<View>()

        for (i in 0 until Prefs.MAX_FAVORITES) {
            val cell = ItemGridCellBinding.inflate(inflater, b.row, false)
            cell.icon.layoutParams = cell.icon.layoutParams.apply { width = iconPx; height = iconPx }
            val fav = favorites.getOrNull(i)
            if (fav != null) {
                val entry = fav.entry
                cell.label.text = entry.label
                cell.icon.setImageBitmap(repo.icon(entry) { cell.icon.setImageBitmap(it) })
                // The predicted slot can change: a small mark says so.
                if (fav.predicted) cell.icon.foreground = activity.getDrawable(R.drawable.fg_predicted)
                cell.root.contentDescription =
                    if (fav.predicted) activity.getString(R.string.favorite_predicted, entry.label) else entry.label
                cell.root.setOnClickListener { v ->
                    dismiss()
                    onLaunch(entry, v)
                }
                cell.root.setOnLongClickListener { v ->
                    onLongPress(entry, v, isPredictedSlot(prefs, i))
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
            b.row.addView(cell.root)
            shown += cell.root
        }
        b.hint.setText(emptyHint)
        b.hint.visibility = if (favorites.all { it == null }) View.VISIBLE else View.GONE

        val popup = PopupWindow(b.root, above.width, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.isOutsideTouchable = true
        popup.elevation = 12f
        popup.animationStyle = android.R.style.Animation_Dialog

        // Sit just above the anchor (the home card), same width and left edge.
        b.root.measure(
            View.MeasureSpec.makeMeasureSpec(above.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // Relative to the anchor itself, so system-bar insets can't shift it onto the card.
        val gap = activity.px(R.dimen.space_s)
        popup.showAsDropDown(above, 0, -(above.height + b.root.measuredHeight + gap), Gravity.START)
        window = popup
        anchor = above
        cells = shown
    }

    /** Redraws the open popup in place, e.g. when the automatic ranking changes under it. */
    fun rerenderIfShowing() {
        val a = anchor
        if (isShowing && a != null) show(a)
    }

    /** Largest icon (up to the usual size) that lets six cells fit across [rowWidthPx]. */
    private fun iconSize(rowWidthPx: Int): Int {
        val cellPadding = 2 * activity.px(R.dimen.space_xs)
        return minOf(activity.px(R.dimen.size_grid_icon), rowWidthPx / Prefs.MAX_FAVORITES - cellPadding)
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }
}
