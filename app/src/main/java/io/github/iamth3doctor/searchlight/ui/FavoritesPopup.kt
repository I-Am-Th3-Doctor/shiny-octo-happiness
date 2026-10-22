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
    private val onLongPress: (AppEntry, View) -> Unit,
) {
    private var window: PopupWindow? = null
    private var anchor: View? = null

    val isShowing get() = window?.isShowing == true

    fun show(above: View) {
        dismiss()
        val b = PopupFavoritesBinding.inflate(LayoutInflater.from(activity))
        val favorites = prefs.favorites.mapNotNull(repo::find)
        val emptyHint = if (prefs.autoFavoritesActive) R.string.favorites_auto_empty_hint else R.string.favorites_empty_hint
        val inflater = LayoutInflater.from(activity)
        val iconPx = iconSize(above.width - b.root.paddingLeft - b.root.paddingRight)

        for (i in 0 until Prefs.MAX_FAVORITES) {
            val cell = ItemGridCellBinding.inflate(inflater, b.row, false)
            cell.icon.layoutParams = cell.icon.layoutParams.apply { width = iconPx; height = iconPx }
            val entry = favorites.getOrNull(i)
            if (entry != null) {
                cell.label.text = entry.label
                cell.icon.setImageBitmap(repo.icon(entry) { cell.icon.setImageBitmap(it) })
                cell.root.contentDescription = entry.label
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
            b.row.addView(cell.root)
        }
        b.hint.setText(emptyHint)
        b.hint.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE

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
