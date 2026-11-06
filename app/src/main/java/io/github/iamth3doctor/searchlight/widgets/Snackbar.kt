package io.github.iamth3doctor.searchlight.widgets

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.ui.Type
import io.github.iamth3doctor.searchlight.ui.color
import io.github.iamth3doctor.searchlight.ui.px
import io.github.iamth3doctor.searchlight.ui.pxF
import io.github.iamth3doctor.searchlight.ui.type

/**
 * A brief message at the bottom of the screen with one action ("Saved: Gym, Fri Nov 6,
 * 8:00–9:00 PM · Undo"), on dark glass like the edit bar. One at a time; it goes by itself after
 * [DURATION_MS], when its action is tapped, or when another replaces it.
 */
class Snackbar private constructor(private val root: ViewGroup, val view: LinearLayout, val message: TextView, val action: TextView?) {

    fun dismiss() {
        root.removeView(view)
        if (current === this) current = null
    }

    companion object {
        const val DURATION_MS = 5_000L

        /** The one showing (tests). */
        var current: Snackbar? = null
            private set

        fun show(activity: Activity, text: CharSequence, actionLabel: CharSequence? = null, onAction: (() -> Unit)? = null): Snackbar {
            current?.dismiss()
            val root = activity.findViewById<FrameLayout>(android.R.id.content)
            val c = activity
            val bar = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.px(R.dimen.space_m), c.px(R.dimen.space_s), c.px(R.dimen.space_s), c.px(R.dimen.space_s))
                minimumHeight = c.px(R.dimen.size_touch)
                background = GradientDrawable().apply {
                    setColor(c.color(R.color.menu_surface))
                    cornerRadius = c.pxF(R.dimen.radius_tile)
                }
                elevation = c.pxF(R.dimen.elevation_raised)
                isClickable = true // touches on it never reach the home screen under it
            }
            val msg = TextView(c).apply {
                type(Type.BODY)
                setTextColor(c.color(R.color.menu_text))
                this.text = text
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            bar.addView(msg, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            val act = if (actionLabel != null) TextView(c).apply {
                type(Type.BODY)
                typeface = Typeface.create(typeface, Typeface.BOLD)
                setTextColor(c.color(R.color.gold))
                this.text = actionLabel
                gravity = Gravity.CENTER
                minWidth = c.px(R.dimen.size_touch)
                minHeight = c.px(R.dimen.size_touch)
                setPadding(c.px(R.dimen.space_s), 0, c.px(R.dimen.space_s), 0)
                val tv = android.util.TypedValue()
                c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                if (tv.resourceId != 0) background = c.getDrawable(tv.resourceId)
            }.also { bar.addView(it) } else null
            val bottom = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())?.bottom ?: 0
            val margin = c.px(R.dimen.space_m)
            root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                setMargins(margin, 0, margin, bottom + margin)
            })
            val snack = Snackbar(root, bar, msg, act)
            act?.setOnClickListener {
                snack.dismiss()
                onAction?.invoke()
            }
            current = snack
            bar.alpha = 0f
            bar.animate().alpha(1f).setDuration(150).start()
            bar.postDelayed({ if (current === snack) snack.dismiss() }, DURATION_MS)
            bar.visibility = View.VISIBLE
            return snack
        }
    }
}
