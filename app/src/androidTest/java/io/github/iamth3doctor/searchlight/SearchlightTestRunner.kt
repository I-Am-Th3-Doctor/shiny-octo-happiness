package io.github.iamth3doctor.searchlight

import android.app.Application
import androidx.test.runner.AndroidJUnitRunner
import io.github.iamth3doctor.searchlight.data.Prefs

/**
 * The device tests' runner: before the app starts, marks the one-time prompts (notification
 * access for badges, accessibility for double-tap to lock) as already asked, so a dialog never
 * covers what another test looks at or taps. The tests of those prompts clear the flag themselves.
 */
class SearchlightTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        app.getSharedPreferences("searchlight", 0).edit()
            .putBoolean(Prefs.KEY_BADGE_ASKED, true)
            .putBoolean(Prefs.KEY_LOCK_ASKED, true)
            .putBoolean(Prefs.KEY_FOCUS_DND_ASKED, true)
            .putBoolean(Prefs.KEY_FOCUS_DND_NOTICE, true)
            .commit()
        super.callApplicationOnCreate(app)
    }
}
