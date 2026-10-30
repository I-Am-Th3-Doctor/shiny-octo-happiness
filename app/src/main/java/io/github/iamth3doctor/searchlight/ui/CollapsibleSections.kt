package io.github.iamth3doctor.searchlight.ui

import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.data.Prefs

/**
 * Collapsible sections for a settings column. In [column], each section title (a view tagged
 * [headerTag]) and every view after it, up to the next title, become one section: the title
 * goes into a header row with a chevron, the rest into a body that tapping the header shows or
 * hides (smoothly). Sections are independent. Views stay the same objects, only re-parented,
 * so code that shows or hides rows inside a section keeps working: a row it shows stays
 * behind a collapsed header until the section is opened.
 *
 * Each section's open/closed state is remembered per [screen] (Prefs); the first section of a
 * screen starts open, the rest closed.
 *
 * Each section sits in its own card: 28dp corners, filled with the theme's text color at a few
 * percent over the page, so it reads in light and dark alike.
 */
object CollapsibleSections {

    /** Tag a section title with this to make it a header; [key] names it in the saved state. */
    fun headerTag(key: String) = "section:$key"

    /** The header row and the body of section [key] (for tests). */
    fun headerOf(root: View, key: String): View? = root.findViewWithTag("sectionHeader:$key")
    fun bodyOf(root: View, key: String): View? = root.findViewWithTag("sectionBody:$key")
    fun cardOf(root: View, key: String): View? = root.findViewWithTag("sectionCard:$key")

    fun apply(column: LinearLayout, screen: String, prefs: Prefs) {
        val children = (0 until column.childCount).map(column::getChildAt)
        val firstHeader = children.indexOfFirst { isHeader(it) }
        if (firstHeader < 0) return
        column.removeAllViews()
        children.take(firstHeader).forEach(column::addView) // the page title etc. stay as they are
        var i = firstHeader
        var first = true
        while (i < children.size) {
            val title = children[i] as TextView
            val key = (title.tag as String).removePrefix("section:")
            var j = i + 1
            while (j < children.size && !isHeader(children[j])) j++
            val body = LinearLayout(column.context).apply {
                orientation = LinearLayout.VERTICAL
                tag = "sectionBody:$key"
                children.subList(i + 1, j).forEach { addView(it) }
            }
            val open = prefs.sectionExpanded(screen, key, default = first)
            val card = card(column, key, top = if (first) ctxPx(column, R.dimen.space_xs) else ctxPx(column, R.dimen.space_s))
            // Inside a card the title needs only a little room above it, not the page's section gap.
            title.setPadding(title.paddingLeft, ctxPx(column, R.dimen.space_m), title.paddingRight, title.paddingBottom)
            card.addView(header(column, title, key, body, open) { expanded ->
                prefs.setSectionExpanded(screen, key, expanded)
            })
            body.visibility = if (open) View.VISIBLE else View.GONE
            card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            first = false
            i = j
        }
    }

    private fun ctxPx(v: View, dimen: Int) = v.context.px(dimen)

    /** A section's card, added to [column]: rounded, a faint tint of the theme's text color. */
    private fun card(column: LinearLayout, key: String, top: Int): LinearLayout {
        val ctx = column.context
        val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val ink = a.getColor(0, 0xFF000000.toInt())
        a.recycle()
        val pad = ctx.px(R.dimen.space_m)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            tag = "sectionCard:$key"
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor((ink and 0x00FFFFFF) or CARD_TINT)
                cornerRadius = ctx.pxF(R.dimen.radius_card)
            }
            clipToOutline = true // the header's tap ripple follows the corners
            setPadding(pad, 0, pad, ctx.px(R.dimen.space_s))
            column.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = top
            })
        }
    }

    private fun isHeader(v: View) = (v.tag as? String)?.startsWith("section:") == true && v is TextView

    private fun header(column: LinearLayout, title: TextView, key: String, body: View, open: Boolean, onToggle: (Boolean) -> Unit): View {
        val ctx = column.context
        val chevron = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_arrow_down)
            imageTintList = ColorStateList.valueOf(title.currentTextColor)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            rotation = if (open) 0f else -90f
        }
        val size = ctx.px(R.dimen.size_chevron)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            tag = "sectionHeader:$key"
            isClickable = true
            isFocusable = true
            val a = ctx.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = a.getDrawable(0)
            a.recycle()
            contentDescription = title.text
            describe(this, open)
            title.tag = null // it's this row now that's the header
            addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(chevron, LinearLayout.LayoutParams(size, size).apply { bottomMargin = title.paddingBottom })
            setOnClickListener {
                val expand = body.visibility != View.VISIBLE
                animate(body, expand)
                chevron.animate().rotation(if (expand) 0f else -90f).setDuration(ANIM_MS).start()
                describe(this, expand)
                onToggle(expand)
            }
        }
    }

    /** Screen readers hear "expanded" / "collapsed" with the title (Android 11+). */
    private fun describe(header: View, open: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            header.stateDescription = header.context.getString(if (open) R.string.section_expanded else R.string.section_collapsed)
        }
    }

    /** Opens or closes [body] by animating its height; it ends at its natural height or gone. */
    private fun animate(body: View, expand: Boolean) {
        (body.getTag(R.id.section_anim) as? ValueAnimator)?.cancel()
        val parent = body.parent as View
        val parentWidth = parent.width - parent.paddingLeft - parent.paddingRight
        val from = if (body.visibility == View.VISIBLE) body.height else 0
        val to = if (expand) {
            body.measure(
                View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            body.measuredHeight
        } else 0
        body.layoutParams.height = from
        body.visibility = View.VISIBLE
        body.requestLayout()
        val anim = ValueAnimator.ofInt(from, to).setDuration(ANIM_MS)
        anim.addUpdateListener {
            body.layoutParams.height = it.animatedValue as Int
            body.requestLayout()
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (cancelled) return
                body.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                if (!expand) body.visibility = View.GONE
                body.requestLayout()
            }
        })
        body.setTag(R.id.section_anim, anim)
        anim.start()
    }

    private const val ANIM_MS = 220L
    /** The card's fill: the text color at ~6%. */
    private const val CARD_TINT = 0x10000000
}
