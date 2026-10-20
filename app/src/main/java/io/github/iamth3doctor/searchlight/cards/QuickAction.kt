package io.github.iamth3doctor.searchlight.cards

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import io.github.iamth3doctor.searchlight.R

/**
 * Everything a Quick Actions slot can be. Stored by [name] in prefs, so reordering or adding
 * entries here never breaks saved choices.
 */
enum class QuickAction(@StringRes val label: Int, @DrawableRes val icon: Int) {
    TORCH(R.string.qa_torch, R.drawable.ic_flashlight),
    WIFI(R.string.qa_wifi, R.drawable.ic_wifi),
    BLUETOOTH(R.string.qa_bluetooth, R.drawable.ic_bluetooth),
    DND(R.string.qa_dnd, R.drawable.ic_dnd),
    BRIGHTNESS(R.string.qa_brightness, R.drawable.ic_brightness),
    ROTATION_LOCK(R.string.qa_rotation, R.drawable.ic_rotation),
    RINGER(R.string.qa_ringer, R.drawable.ic_ringer),
    AIRPLANE(R.string.qa_airplane, R.drawable.ic_airplane),
    VOLUME(R.string.qa_volume, R.drawable.ic_volume);

    companion object {
        const val SLOTS = 4
        val DEFAULTS = listOf(TORCH, WIFI, BLUETOOTH, DND)
    }
}
