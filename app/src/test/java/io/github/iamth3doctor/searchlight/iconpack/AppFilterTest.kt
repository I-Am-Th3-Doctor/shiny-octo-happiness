package io.github.iamth3doctor.searchlight.iconpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppFilterTest {

    private fun el(name: String, vararg attrs: Pair<String, String>) = AppFilter.Element(name, attrs.toMap())

    @Test fun componentsInEveryCommonSpelling() {
        assertEquals("com.a/com.a.Main", AppFilter.component("ComponentInfo{com.a/com.a.Main}"))
        assertEquals("com.a/com.a.Main", AppFilter.component(" ComponentInfo{com.a/.Main} "))
        assertEquals("com.a/com.b.Main", AppFilter.component("com.a/com.b.Main"))
        assertNull(AppFilter.component("ComponentInfo{:LAUNCHER_ACTION_APP_DRAWER}"))
        assertNull(AppFilter.component(""))
    }

    @Test fun readsItemsCalendarsAndLayers() {
        val f = AppFilter.parse(sequenceOf(
            el("resources"),
            el("iconback", "img2" to "back_b", "img1" to "back_a"),
            el("iconmask", "img1" to "mask"),
            el("iconupon", "img1" to "upon"),
            el("scale", "factor" to "0.8"),
            el("item", "component" to "ComponentInfo{com.a/com.a.Main}", "drawable" to "a_icon"),
            el("item", "component" to "ComponentInfo{com.a/com.a.Main}", "drawable" to "a_later"), // first wins
            el("item", "component" to "ComponentInfo{com.b/.Start}", "drawable" to ""), // no drawable: skipped
            el("calendar", "component" to "ComponentInfo{com.cal/com.cal.Main}", "prefix" to "calendar_"),
        ))
        assertEquals(mapOf("com.a/com.a.Main" to "a_icon"), f.components)
        assertEquals(mapOf("com.cal/com.cal.Main" to "calendar_"), f.calendars)
        assertEquals(listOf("back_a", "back_b"), f.backs)
        assertEquals("mask", f.mask)
        assertEquals("upon", f.upon)
        assertEquals(0.8f, f.scale)
        assertTrue(f.stylesFallbacks)
    }

    @Test fun plainPackLeavesFallbacksAlone() {
        val f = AppFilter.parse(sequenceOf(el("item", "component" to "ComponentInfo{com.a/com.a.Main}", "drawable" to "a")))
        assertFalse(f.stylesFallbacks)
        assertEquals(1f, f.scale)
    }

    @Test fun badScaleIsIgnored() {
        val f = AppFilter.parse(sequenceOf(
            el("item", "component" to "ComponentInfo{com.a/com.a.Main}", "drawable" to "a"),
            el("scale", "factor" to "lots"),
            el("scale", "factor" to "-1"),
        ))
        assertEquals(1f, f.scale)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aFileThatThemesNothingIsNotAPack() {
        AppFilter.parse(sequenceOf(el("resources"), el("scale", "factor" to "0.9")))
    }

    @Test fun nameMatchCandidates() {
        assertEquals(listOf("com_android_chrome", "com_google_android_apps_chrome_main"),
            AppFilter.nameCandidates("com.android.chrome", "com.google.android.apps.chrome.Main"))
    }
}
