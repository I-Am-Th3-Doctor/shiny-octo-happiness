package io.github.iamth3doctor.searchlight

import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The search field must take the standard editing keys while results are showing: Backspace at
 * the end and mid-text, held Backspace (key repeat), from a hardware key and from an on-screen
 * keyboard (which deletes through its InputConnection, or sends key events through it).
 */
@RunWith(AndroidJUnit4::class)
class SearchEditingTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var home: ActivityScenario<MainActivity>

    @Before fun openSearch() {
        home = ActivityScenario.launch(MainActivity::class.java)
        home.onActivity { it.findViewById<android.view.View>(R.id.searchPill).performClick() }
        instrumentation.waitForIdleSync()
        instrumentation.sendStringSync("chrome")
        instrumentation.waitForIdleSync()
        assertEquals("typed", "chrome", text())
    }

    @After fun close() = home.close()

    private fun text(): String {
        var t = ""
        home.onActivity { t = it.findViewById<EditText>(R.id.searchInput).text.toString() }
        return t
    }

    private fun focus(): String {
        var f = ""
        home.onActivity { f = it.currentFocus?.let { v -> "${v.javaClass.simpleName}#${runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("?")}" } ?: "none" }
        return f
    }

    private fun cursorAt(index: Int) = home.onActivity { it.findViewById<EditText>(R.id.searchInput).setSelection(index) }

    private fun check(what: String, expected: String) {
        instrumentation.waitForIdleSync()
        assertEquals("$what (focus: ${focus()})", expected, text())
    }

    @Test fun hardwareBackspaceAtEnd() {
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
        check("key DEL at end", "chrom")
    }

    @Test fun hardwareBackspaceMidText() {
        cursorAt(2)
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DEL)
        check("key DEL after 'ch'", "crome")
    }

    @Test fun heldBackspaceKeepsDeleting() {
        val t = SystemClock.uptimeMillis()
        for (repeat in 0..3) instrumentation.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL, repeat))
        instrumentation.sendKeySync(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL, 0))
        check("held DEL (1 press + 3 repeats)", "ch")
    }

    @Test fun keyboardDeletesThroughInputConnection() {
        cursorAt(3)
        home.onActivity { a ->
            val ic = a.findViewById<EditText>(R.id.searchInput).onCreateInputConnection(EditorInfo())!!
            ic.deleteSurroundingText(1, 0)
        }
        check("deleteSurroundingText after 'chr'", "chome")
    }

    @Test fun keyboardSendsBackspaceKeyEvents() {
        home.onActivity { a ->
            val ic = a.findViewById<EditText>(R.id.searchInput).onCreateInputConnection(EditorInfo())!!
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
        check("IME key event DEL", "chrom")
    }
}
