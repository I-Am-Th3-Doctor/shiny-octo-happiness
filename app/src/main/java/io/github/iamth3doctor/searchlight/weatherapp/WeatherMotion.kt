package io.github.iamth3doctor.searchlight.weatherapp

import android.content.Context
import android.os.PowerManager

/** Whether the weather screen's small animations (the precip card's island, the metric cards' pictures) hold still: in battery saver. */
object WeatherMotion {
    /** Tests: stand in for battery saver (null: the real setting). */
    @Volatile var powerSaveOverride: Boolean? = null

    fun powerSave(context: Context): Boolean = powerSaveOverride
        ?: (context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true)
}
