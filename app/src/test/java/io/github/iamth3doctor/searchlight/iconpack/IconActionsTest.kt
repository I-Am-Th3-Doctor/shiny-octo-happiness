package io.github.iamth3doctor.searchlight.iconpack

import io.github.iamth3doctor.searchlight.iconpack.IconTheme.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IconActionsTest {

    /** The table as it ships, in the theme folder. */
    private val shipped by lazy {
        IconActions.parse(File("src/main/assets/icontheme/${IconActions.FILE}").readText())
    }

    @Test fun everyAppInTheTableHasItsAssignedAction() {
        assertEquals("every app of the table", 171, shipped.rows.size)
        assertEquals("every action one of the moves", emptyList<String>(), shipped.skipped)
        // The 16 moves of the vocabulary, all of them used.
        val vocabulary = listOf("ring", "spin", "shutter", "bounce", "swing", "flip", "pop", "wobble",
            "pulse", "slide", "drop", "chat", "tick", "wave", "flash", "zoom")
        assertEquals(vocabulary.toSet(), shipped.rows.map { it.action }.toSet())
        for (row in shipped.rows) assertEquals(row.label, Move.valueOf(row.action.uppercase()), row.move)
        // Each row reached through its own components, or else its label.
        for (row in shipped.rows) {
            val found = if (row.components.isNotEmpty()) row.components.map { shipped.moveFor("$it/.Main", null) }
            else listOf(shipped.moveFor("com.unknown/.Main", row.label.removeSuffix("...").trim()))
            if (row.label.contains("...")) continue
            // Two rows share the label "Authenticator": the components tell them apart.
            if (row.components.isEmpty() && shipped.rows.count { IconActions.normal(it.label) == IconActions.normal(row.label) } > 1) continue
            assertTrue("${row.label}: $found", found.all { it == row.move })
        }
    }

    @Test fun theSpotChecks() {
        assertEquals("Phone rings", Move.RING, shipped.moveFor("com.google.android.dialer/com.google.android.dialer.extensions.GoogleDialtactsActivity", "Phone"))
        assertEquals("Messages chat-pops", Move.CHAT, shipped.moveFor("com.google.android.apps.messaging/.ui.ConversationListActivity", "Messages"))
        assertEquals("Camera shutter-clicks", Move.SHUTTER, shipped.moveFor("com.motorola.camera3/com.motorola.camera.Camera", "Camera"))
        assertEquals(Move.SHUTTER, shipped.moveFor("com.android.camera2/com.android.camera.CameraLauncher", "Camera"))
        assertEquals("Maps pin-drops", Move.DROP, shipped.moveFor("com.google.android.apps.maps/com.google.android.maps.MapsActivity", "Maps"))
        assertEquals("Clock ticks", Move.TICK, shipped.moveFor("com.google.android.deskclock/com.android.deskclock.DeskClock", "Clock"))
        assertEquals("Settings spins", Move.SPIN, shipped.moveFor("com.android.settings/.Settings", "Settings"))
        // The two Authenticators, apart by their packages.
        assertEquals(Move.TICK, shipped.moveFor("com.google.android.apps.authenticator2/.Main", "Authenticator"))
        assertEquals(Move.FLIP, shipped.moveFor("com.azure.authenticator/.Main", "Authenticator"))
    }

    @Test fun byComponentThenLabelThenAStubThenNothing() {
        val a = IconActions.parse("""
            Some notes. | not a table row
            | App | Component | Action | Fit |
            |---|---|---|---|
            | Phone | `com.a.dialer`, `com.b.dialer` | ring | call |
            | Exact | `com.c/.Main` | zoom | exact |
            | Cinevault |  | slide | film |
            | Lowe’s (?) |  | pop | store |
            | Moto Secure |  | flip | lock |
            | Moto ... (?) |  | pulse | stub |
            | O... (?) |  | pop | too short |
            | Odd |  | jiggle | not a move |
        """.trimIndent())
        assertEquals(8, a.rows.size)
        assertEquals(listOf("Odd: \"jiggle\""), a.skipped)
        assertEquals(Move.RING, a.moveFor("com.b.dialer/com.b.dialer.Main", "Telefon"))
        assertEquals("the exact component", Move.ZOOM, a.moveFor("com.c/com.c.Main", null))
        assertNull("another activity of that package isn't it", a.moveFor("com.c/com.c.Other", null))
        assertEquals("by label", Move.SLIDE, a.moveFor("com.x/.Main", "Cinevault"))
        assertEquals("label, (?) and curly apostrophes aside", Move.POP, a.moveFor("com.x/.Main", "LOWE'S"))
        assertEquals("the full label first", Move.FLIP, a.moveFor("com.x/.Main", "Moto Secure"))
        assertEquals("then a stub", Move.PULSE, a.moveFor("com.x/.Main", "Moto Actions"))
        assertNull("a stub too short to name one app", a.moveFor("com.x/.Main", "Opera"))
        assertNull("not in the table: a generic move", a.moveFor("com.x/.Main", "Chrome"))
        assertNull(a.moveFor("com.x/.Main", "Odd"))
        assertNull(IconActions.EMPTY.moveFor("com.a.dialer/.Main", "Phone"))
    }
}
