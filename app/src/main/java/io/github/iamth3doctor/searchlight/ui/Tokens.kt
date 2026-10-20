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

/**
 * Pads this view by the system bars and display cutout on top of its own padding, so nothing
 * draws under the status bar, the navigation bar or a camera cutout. Needed on Android 15+,
 * which lays every window out edge to edge.
 */
fun android.view.View.padForSystemBars() {
    val l = paddingLeft
    val t = paddingTop
    val r = paddingRight
    val b = paddingBottom
    androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout(),
        )
        v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
        insets
    }
    androidx.core.view.ViewCompat.requestApplyInsets(this)
}

/**
 * The same edge-to-edge setup on every Android version (11-14 opt in, 15+ enforce it), with
 * status/navigation bar icons dark on light screens and light on dark ones.
 */
fun android.app.Activity.edgeToEdge(lightBackground: Boolean) {
    // Bar colors are transparent in the theme; here we only stop the decor from fitting them.
    androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
    androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = lightBackground
        isAppearanceLightNavigationBars = lightBackground
    }
}

/** Whether the system-themed screens (settings, pickers) are in their light variant. */
fun Context.isLightTheme(): Boolean =
    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
        android.content.res.Configuration.UI_MODE_NIGHT_YES
