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
 * The 3x2 grid of favorites shown when you long-press the magnifier.
 *
 * Not focusable on purpose: the keyboard stays up, so dismissing the grid (tap outside, or just
 * start typing) drops you straight back into search.
 */
class FavoritesPopup(
    private val activity: Activity,
    private val repo: AppRepository,
    private val prefs: Prefs,
    private val onLaunch: (AppEntry, View) -> Unit,
) {
    private var window: PopupWindow? = null

    val isShowing get() = window?.isShowing == true

    fun show(above: View) {
        dismiss()
        val b = PopupFavoritesBinding.inflate(LayoutInflater.from(activity))
        val favorites = prefs.favorites.mapNotNull(repo::find)
        val inflater = LayoutInflater.from(activity)

        for (i in 0 until Prefs.MAX_FAVORITES) {
            val cell = ItemGridCellBinding.inflate(inflater, b.grid, false)
            val entry = favorites.getOrNull(i)
            if (entry != null) {
                cell.label.text = entry.label
                cell.icon.setImageBitmap(repo.icon(entry) { cell.icon.setImageBitmap(it) })
                cell.root.contentDescription = entry.label
                cell.root.setOnClickListener { v ->
                    dismiss()
                    onLaunch(entry, v)
                }
            } else {
                cell.icon.setImageResource(R.drawable.ic_add)
                cell.icon.setBackgroundResource(R.drawable.bg_empty_slot)
                cell.icon.alpha = 0.6f
                cell.label.text = ""
                cell.root.setOnClickListener {
                    Toast.makeText(activity, R.string.favorites_empty_hint, Toast.LENGTH_SHORT).show()
                }
            }
            b.grid.addView(cell.root)
        }
        b.hint.visibility = if (favorites.isEmpty()) View.VISIBLE else View.GONE

        val popup = PopupWindow(b.root, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false)
        popup.setBackgroundDrawable(ColorDrawable(0))
        popup.isOutsideTouchable = true
        popup.elevation = 12f
        popup.animationStyle = android.R.style.Animation_Dialog

        // Position the grid just above the anchor (the search bar), centered on it.
        val loc = IntArray(2)
        above.getLocationInWindow(loc)
        val rootHeight = activity.window.decorView.height
        val gap = activity.px(R.dimen.space_s)
        val centerX = loc[0] + above.width / 2 - activity.window.decorView.width / 2
        popup.showAtLocation(above, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, centerX, rootHeight - loc[1] + gap)
        window = popup
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }
}
