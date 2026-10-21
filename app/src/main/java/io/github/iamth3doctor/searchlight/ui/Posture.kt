package io.github.iamth3doctor.searchlight.ui

import android.content.res.Configuration
import kotlin.math.min

/**
 * Folded (the cover screen) or unfolded (the big inner screen), from the window's size class:
 * unfolded when even its shorter side is at least [UNFOLDED_MIN_DP] (Android's "medium" width).
 * Using the shorter side keeps a folded phone turned sideways "folded".
 */
enum class Posture {
    FOLDED, UNFOLDED;

    companion object {
        const val UNFOLDED_MIN_DP = 600

        fun of(widthDp: Int, heightDp: Int) = if (min(widthDp, heightDp) >= UNFOLDED_MIN_DP) UNFOLDED else FOLDED

        fun of(config: Configuration) = of(config.screenWidthDp, config.screenHeightDp)
    }
}
