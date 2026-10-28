package io.github.iamth3doctor.searchlight.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R

/**
 * The stock-style long-press menu for an app: its own shortcuts (each with the app's icon for it)
 * on top, a hairline, then the launcher's actions. Its own window over everything on the home
 * screen. Opens below the icon, or above it when there isn't room, always inside the screen's
 * safe area; when it's taller than the room there, its rows scroll. Closes on any choice, a tap
 * outside, Back, or a fold / unfold.
 */
class AppMenu(private val activity: Activity) {

    class Item(val label: CharSequence, val bitmap: Bitmap? = null, val iconRes: Int = 0, val action: () -> Unit)

    private var window: PopupWindow? = null

    val isShowing get() = window?.isShowing == true

    /** The open menu's card (for tests). */
    internal val contentView: View? get() = window?.contentView

    /** The open menu's rows, inside its scrolling list (for tests). */
    internal var rowsView: ViewGroup? = null
        private set

    fun show(anchor: View, shortcuts: List<Item>, actions: List<Item>, onDismiss: () -> Unit) {
        dismiss()
        val rows = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        // The rows scroll inside the rounded card when there are more than the window has room for.
        val scroll = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFillViewport = true
            addView(rows)
        }
        val content = FrameLayout(activity).apply {
            val pad = activity.px(R.dimen.space_xs)
            setPadding(0, pad, 0, pad)
            background = GradientDrawable().apply {
                setColor(activity.getColor(R.color.menu_surface))
                cornerRadius = activity.pxF(R.dimen.radius_menu)
                setStroke(activity.px(R.dimen.stroke_glass), activity.getColor(R.color.glass_border))
            }
            elevation = activity.pxF(R.dimen.space_s)
            clipToOutline = true
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        val popup = PopupWindow(content, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        shortcuts.forEach { rows.addView(row(it, popup, tinted = false)) }
        if (shortcuts.isNotEmpty() && actions.isNotEmpty()) {
            rows.addView(View(activity).apply { setBackgroundColor(activity.getColor(R.color.glass_border)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.px(R.dimen.stroke_glass)).apply {
                    val m = activity.px(R.dimen.space_xs)
                    setMargins(0, m, 0, m)
                })
        }
        actions.forEach { rows.addView(row(it, popup, tinted = true)) }

        // Measured against the home screen's own window, also when the icon is in another popup
        // (the favorites row is one): that window is the screen the menu has to stay inside.
        val root = activity.window.decorView
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)

        // Its size: as wide as the labels need (within limits), as tall as all its rows.
        val bounds = safeArea(root)
        rows.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = rows.measuredWidth
            .coerceIn(activity.px(R.dimen.size_menu_min_width), activity.px(R.dimen.size_menu_max_width))
            .coerceAtMost(bounds.width)
        rows.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        val natural = rows.measuredHeight + content.paddingTop + content.paddingBottom

        // Where: below the icon if it fits, else above, else the roomier side, scrolling.
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val ax = loc[0] - rootLoc[0]
        val ay = loc[1] - rootLoc[1]
        val place = MenuPlacement.place(
            MenuPlacement.Box(ax, ay, ax + anchor.width, ay + anchor.height),
            bounds, width, natural, activity.px(R.dimen.space_xs),
        )
        popup.width = place.width
        popup.height = place.height

        popup.setBackgroundDrawable(null)
        popup.isOutsideTouchable = true
        popup.isClippingEnabled = false // placed exactly, already inside the safe area
        popup.inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED // the keyboard stays as it is
        popup.elevation = activity.pxF(R.dimen.space_s)
        popup.animationStyle = android.R.style.Animation_Dialog

        // Fold / unfold (or any resize) while open: its place no longer fits, so it closes.
        val resized = View.OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) popup.dismiss()
        }
        root.addOnLayoutChangeListener(resized)
        popup.setOnDismissListener {
            root.removeOnLayoutChangeListener(resized)
            if (window === popup) window = null
            onDismiss()
        }
        popup.showAtLocation(root, Gravity.TOP or Gravity.START, place.x, place.y)
        window = popup
        rowsView = rows
    }

    /**
     * The part of the window a menu may use, in window coordinates: inside the status bar, the
     * navigation bar, the display cutout and the keyboard, and [R.dimen.space_menu_margin] from
     * every edge.
     */
    private fun safeArea(root: View): MenuPlacement.Box {
        val insets = root.rootWindowInsets?.getInsets(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
        )
        val m = activity.px(R.dimen.space_menu_margin)
        return MenuPlacement.Box(
            (insets?.left ?: 0) + m,
            (insets?.top ?: 0) + m,
            root.width - (insets?.right ?: 0) - m,
            root.height - (insets?.bottom ?: 0) - m,
        )
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
