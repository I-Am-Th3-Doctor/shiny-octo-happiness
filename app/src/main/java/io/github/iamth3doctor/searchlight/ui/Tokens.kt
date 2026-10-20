package io.github.iamth3doctor.searchlight.ui

import android.content.Context
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.DimenRes
import androidx.annotation.StyleRes
import io.github.iamth3doctor.searchlight.R

/*
 * Reading design tokens (res/values/tokens.xml) from code, so views built in Kotlin use the
 * same scale as XML layouts. Nothing in Kotlin should hardcode a dp, sp or color.
 */

fun Context.px(@DimenRes token: Int): Int = resources.getDimensionPixelSize(token)

fun Context.pxF(@DimenRes token: Int): Float = resources.getDimension(token)

fun Context.color(@ColorRes token: Int): Int = getColor(token)

/** Applies one of the type-scale styles. They set only size/weight, never color. */
fun TextView.type(@StyleRes style: Int) = setTextAppearance(style)

object Type {
    val GLANCE = R.style.TextAppearance_Searchlight_Glance
    val TILE_TITLE = R.style.TextAppearance_Searchlight_TileTitle
    val BODY = R.style.TextAppearance_Searchlight_Body
    val CAPTION = R.style.TextAppearance_Searchlight_Caption
}

/** The theme's primary / secondary text colors (settings screens follow the system light/dark theme). */
fun Context.primaryText() = themeColor(android.R.attr.textColorPrimary, R.color.on_surface)

fun Context.secondaryText() = themeColor(android.R.attr.textColorSecondary, R.color.on_surface_dim)

private fun Context.themeColor(attr: Int, @ColorRes fallback: Int): android.content.res.ColorStateList {
    val a = obtainStyledAttributes(intArrayOf(attr))
    try {
        return a.getColorStateList(0) ?: android.content.res.ColorStateList.valueOf(color(fallback))
    } finally {
        a.recycle()
    }
}
