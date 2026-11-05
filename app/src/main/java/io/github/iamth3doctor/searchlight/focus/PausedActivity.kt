package io.github.iamth3doctor.searchlight.focus

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.searchlight

/**
 * "Paused during focus": what opening a restricted app from Searchlight shows while Focus is on
 * with Restricted apps set to Blocked. Two ways out only: back, or leave Focus (which restores
 * everything). There's deliberately no "open anyway".
 */
class PausedActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_paused)
        val label = intent.getStringExtra(EXTRA_LABEL).orEmpty()
        findViewById<TextView>(R.id.pausedMessage).text = getString(R.string.paused_message, label)
        findViewById<Button>(R.id.pausedBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.pausedExitFocus).setOnClickListener {
            searchlight.prefs.focusMode = false // every route out restores the same (DND, apps, glass, layout)
            finish()
        }
    }

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_PACKAGE = "package"
    }
}
