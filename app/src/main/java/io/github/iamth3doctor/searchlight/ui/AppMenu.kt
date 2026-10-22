package io.github.iamth3doctor.searchlight.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R

/**
 * The stock-style long-press menu for an app: its own shortcuts (each with the app's icon for it)
 * on top, a hairline, then the launcher's actions. Opens below the icon, or above it when there
 * isn't room, and closes on any choice, a tap outside, or Back.
 */
class AppMenu(private val activity: Activity) {

    class Item(val label: CharSequence, val bitmap: Bitmap? = null, val iconRes: Int = 0, val action: () -> Unit)

    private var window: PopupWindow? = null

    val isShowing get() = window?.isShowing == true

    /** The open menu's rows (for tests). */
    internal val contentView: View? get() = window?.contentView

    fun show(anchor: View, shortcuts: List<Item>, actions: List<Item>, onDismiss: () -> Unit) {
        dismiss()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = activity.px(R.dimen.space_xs)
            setPadding(0, pad, 0, pad)
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.menu_surface))
                cornerRadius = activity.pxF(R.dimen.radius_menu)
                setStroke(activity.px(R.dimen.stroke_glass), activity.getColor(R.color.glass_border))
            }
            elevation = activity.pxF(R.dimen.space_s)
            clipToOutline = true
        }
        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        shortcuts.forEach { content.addView(row(it, popup, tinted = false)) }
        if (shortcuts.isNotEmpty() && actions.isNotEmpty()) {
            content.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.glass_border)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.px(R.dimen.stroke_glass)).apply {
                    val m = activity.px(R.dimen.space_xs)
                    setMargins(0, m, 0, m)
                })
        }
        actions.forEach { content.addView(row(it, popup, tinted = true)) }

        popup.setBackgroundDrawable(null)
        popup.isOutsideTouchable = true
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED // the keyboard stays as it is
        popup.elevation = activity.pxF(R.dimen.space_s)
        popup.animationStyle = android.R.style.Animation_Dialog
        popup.setOnDismissListener {
            if (window === popup) window = null
            onDismiss()
        }

        // Centered on the icon; PopupWindow flips it above the icon when there's no room below.
        content.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = content.measuredWidth.coerceIn(activity.px(R.dimen.size_menu_min_width), activity.px(R.dimen.size_menu_max_width))
        popup.width = width
        popup.showAsDropDown(anchor, (anchor.width - width) / 2, 0, Gravity.START)
        window = popup
    }

    private fun row(item: Item, popup: PopupWindow, tinted: Boolean): View {
        val icon = ImageView(activity).apply {
            if (item.bitmap != null) setImageBitmap(item.bitmap) else if (item.iconRes != 0) setImageResource(item.iconRes)
            if (tinted) imageTintList = ColorStateList.valueOf(activity.getColor(R.color.menu_text))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val label = TextView(activity).apply {
            text = item.label
            type(Type.BODY)
            setTextColor(activity.getColor(R.color.menu_text))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val hPad = activity.px(R.dimen.space_m)
        return LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = activity.px(R.dimen.size_touch)
            setPadding(hPad, 0, hPad, 0)
            setBackgroundResource(selectable())
            isClickable = true
            contentDescription = item.label
            val size = activity.px(R.dimen.size_menu_icon)
            addView(icon, LinearLayout.LayoutParams(size, size))
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = hPad })
            setOnClickListener {
                popup.dismiss()
                item.action()
            }
        }
    }

    private fun selectable(): Int {
        val a = activity.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        try {
            return a.getResourceId(0, 0)
        } finally {
            a.recycle()
        }
    }

    fun dismiss() {
        window?.dismiss()
        window = null
    }
}
