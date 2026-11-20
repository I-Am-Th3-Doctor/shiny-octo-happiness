package io.github.iamth3doctor.searchlight.weatherapp

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.View

/**
 * The weather's "no color" rule, enforced where it's drawn: a view given this layer paint shows
 * everything under it (the living scene, icons, any accent) in grays, whatever colors they use.
 */
object Monochrome {
    fun paint(): Paint = Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }

    /** Draws [v] (and all it holds) in grays. */
    fun apply(v: View) = v.setLayerType(View.LAYER_TYPE_HARDWARE, paint())
}
