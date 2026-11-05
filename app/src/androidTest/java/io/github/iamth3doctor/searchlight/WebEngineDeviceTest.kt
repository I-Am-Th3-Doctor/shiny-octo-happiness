package io.github.iamth3doctor.searchlight

import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.iamth3doctor.searchlight.data.WebEngine
import io.github.iamth3doctor.searchlight.search.SearchResult
import io.github.iamth3doctor.searchlight.ui.Actions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Web search engines in Settings: the four originals first and the default unchanged, the new
 * presets, and a Custom engine (checked before saving, kept across restarts and switches), used
 * by the web pill; prefix shortcuts unaffected.
 */
@RunWith(AndroidJUnit4::class)
class WebEngineDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = instrumentation.targetContext.applicationContext as SearchlightApp
    private val pkg = app.packageName
    private val prefix = InstrumentationRegistry.getArguments().getString("shotPrefix") ?: "device"
    private val dir = "/sdcard/Download/searchlight-shots"
    private val sp get() = app.getSharedPreferences("searchlight", 0)
    private val keys = listOf("web_engine", "custom_engine_name", "custom_engine_url")
    private var saved = emptyMap<String, String?>()

    private fun shell(cmd: String): String =
        instrumentation.uiAutomation.executeShellCommand(cmd).use { String(java.io.FileInputStream(it.fileDescriptor).readBytes()) }

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)

    @Before fun setUp() {
        saved = keys.associateWith { sp.getString(it, null) }
        sp.edit().apply { keys.forEach(::remove) }.commit()
        shell("mkdir -p $dir")
        shell("cmd role add-role-holder --user 0 android.app.role.HOME $pkg")
    }

    @After fun restore() {
        sp.edit().apply { for ((k, v) in saved) if (v == null) remove(k) else putString(k, v) }.commit()
    }

    private fun settings(block: (ActivityScenario<SettingsActivity>) -> Unit) =
        ActivityScenario.launch(SettingsActivity::class.java).use { s -> Thread.sleep(800); block(s) }

    /** Opens the engine list and picks the [index]th entry, as a tap would. */
    private fun pick(s: ActivityScenario<SettingsActivity>, index: Int): List<String> {
        var labels = emptyList<String>()
        s.onActivity { a -> a.findViewById<View>(R.id.webEngineRow).performClick() }
        Thread.sleep(600)
        s.onActivity { a ->
            val list = a.engineDialog!!.listView
            labels = (0 until list.adapter.count).map { list.adapter.getItem(it).toString() }
            list.performItemClick(null, index, index.toLong())
        }
        Thread.sleep(600)
        return labels
    }

    private fun summary(s: ActivityScenario<SettingsActivity>): String {
        var t = ""
        s.onActivity { a -> t = a.findViewById<TextView>(R.id.webEngineValue).text.toString() }
        return t
    }

    private fun form(s: ActivityScenario<SettingsActivity>, name: String, url: String) = s.onActivity { a ->
        a.findViewById<EditText>(R.id.customEngineName).setText(name)
        a.findViewById<EditText>(R.id.customEngineUrl).setText(url)
        a.findViewById<View>(R.id.customEngineSave).performClick()
    }

    private fun shown(s: ActivityScenario<SettingsActivity>, id: Int): Boolean {
        var v = false
        s.onActivity { a -> v = a.findViewById<View>(id).visibility == View.VISIBLE }
        return v
    }

    @Test fun presetsCustomAndTheDefault() {
        // A new install: Google, as before.
        assertEquals(WebEngine.GOOGLE, app.prefs.webEngine)
        val custom = WebEngine.entries.indexOf(WebEngine.CUSTOM)
        settings { s ->
            assertEquals("Google", summary(s))
            // The list: the four originals first, then the new presets, then Custom.
            val labels = pick(s, WebEngine.entries.indexOf(WebEngine.ECOSIA))
            assertEquals(listOf("Google", "DuckDuckGo", "Brave", "Bing", "Startpage", "Ecosia", "Qwant", "Yahoo", "Mojeek", "Custom"), labels)
            assertEquals("Ecosia", summary(s))

            // Custom: refused with an error under the field, nothing saved, until it's right.
            pick(s, custom)
            assertTrue("fields open", shown(s, R.id.customEngineForm))
            form(s, "My Search", "http://search.example.com/?q=%s")
            assertTrue(shown(s, R.id.customEngineUrlError))
            assertEquals(WebEngine.ECOSIA, app.prefs.webEngine)
            assertEquals("", app.prefs.customEngineName)
            form(s, "My Search", "https://search.example.com/?q=")
            assertTrue(shown(s, R.id.customEngineUrlError))
            form(s, "", "https://search.example.com/?q=%s")
            assertTrue(shown(s, R.id.customEngineNameError))
            assertEquals(WebEngine.ECOSIA, app.prefs.webEngine)
            shell("screencap -p $dir/${prefix}_font1.0_48-custom-engine-error.png")
            form(s, "My Search", "https://search.example.com/?q=%s")
            assertFalse("saved: fields close", shown(s, R.id.customEngineForm))
            assertEquals(WebEngine.CUSTOM, app.prefs.webEngine)
            assertEquals("My Search", summary(s))
        }
        // A restart: still there, and Custom opens with the saved values in.
        settings { s ->
            assertEquals("My Search", summary(s))
            val labels = pick(s, custom)
            assertEquals("Custom: My Search", labels.last())
            s.onActivity { a ->
                assertEquals("My Search", a.findViewById<EditText>(R.id.customEngineName).text.toString())
                assertEquals("https://search.example.com/?q=%s", a.findViewById<EditText>(R.id.customEngineUrl).text.toString())
            }
            // Preset, then Custom again: the saved one is back.
            pick(s, WebEngine.entries.indexOf(WebEngine.MOJEEK))
            assertEquals("Mojeek", summary(s))
            assertEquals("My Search", app.prefs.customEngineName)
            pick(s, custom)
            assertEquals(WebEngine.CUSTOM, app.prefs.webEngine)
            assertEquals("My Search", summary(s))
        }

        // Home: the web pill names it and opens it; prefix shortcuts still go to their apps.
        ActivityScenario.launch(MainActivity::class.java).use { h ->
            Thread.sleep(1500)
            h.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("lofi beats") }
            Thread.sleep(900)
            h.onActivity { a ->
                assertEquals(a.getString(R.string.web_search_for, "My Search", "lofi beats"), a.findViewById<TextView>(R.id.webPillText).text.toString())
            }
            shell("screencap -p $dir/${prefix}_font1.0_49-custom-engine-pill.png")
            h.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("y lofi") }
            Thread.sleep(900)
            h.onActivity { a ->
                val shortcut = a.currentResultsForTest().filterIsInstance<SearchResult.Shortcut>().single()
                assertEquals("YouTube", shortcut.match.target.label)
                assertEquals("lofi", shortcut.match.query)
            }
            h.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("lofi beats") }
            Thread.sleep(900)
            h.onActivity { a -> a.findViewById<View>(R.id.webPill).performClick() }
            Thread.sleep(1500)
            assertEquals("https://search.example.com/?q=lofi+beats", Actions.lastWebSearchUrl)
        }
        shell("input keyevent KEYCODE_HOME")
        Thread.sleep(1000)

        // Each new preset opens its own URL with the query in.
        val expected = mapOf(
            WebEngine.STARTPAGE to "https://www.startpage.com/sp/search?query=lofi+beats",
            WebEngine.ECOSIA to "https://www.ecosia.org/search?q=lofi+beats",
            WebEngine.QWANT to "https://www.qwant.com/?q=lofi+beats",
            WebEngine.YAHOO to "https://search.yahoo.com/search?p=lofi+beats",
            WebEngine.MOJEEK to "https://www.mojeek.com/search?q=lofi+beats",
        )
        for ((engine, url) in expected) {
            main { app.prefs.webEngine = engine }
            ActivityScenario.launch(MainActivity::class.java).use { h ->
                Thread.sleep(1200)
                h.onActivity { it.findViewById<EditText>(R.id.searchInput).setText("lofi beats") }
                Thread.sleep(800)
                h.onActivity { a -> a.findViewById<View>(R.id.webPill).performClick() }
                Thread.sleep(1200)
                assertEquals(engine.name, url, Actions.lastWebSearchUrl)
            }
            shell("input keyevent KEYCODE_HOME")
            Thread.sleep(800)
        }
    }
}
