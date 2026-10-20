package io.github.iamth3doctor.searchlight.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileIndexTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = System.currentTimeMillis()

    private fun touch(path: String, ageDays: Int = 100) {
        val f = File(tmp.root, path)
        f.parentFile.mkdirs()
        f.writeText("x")
        f.setLastModified(now - ageDays * 86_400_000L)
    }

    private fun build(): FileIndex {
        touch("Download/2024_Tax_Return.pdf")
        touch("Download/taxi receipt.jpg")
        touch("Download/syntax-notes.txt")
        touch("Documents/Résumé.docx")
        touch("Documents/Projects/searchlight.zip")
        touch("DCIM/Camera/IMG_0001.jpg", ageDays = 1)
        touch("Android/data/com.secret/cache.bin")
        touch("Android/media/com.whatsapp/WhatsApp/Media/Doc/menu.pdf")
        touch(".hidden/stuff.txt")
        touch("Music/.nomedia")
        return FileIndex.scan(tmp.root, now)!!
    }

    private fun names(index: FileIndex, q: String) = index.search(q, 10, now).map { it.name }

    @Test fun indexesEverythingButHiddenAndAppPrivate() {
        val all = build().entries.map { it.name }.toSet()
        assertTrue("2024_Tax_Return.pdf" in all)
        assertTrue("menu.pdf" in all)
        assertTrue("Projects" in all)
        assertTrue("cache.bin" !in all)
        assertTrue("stuff.txt" !in all)
        assertTrue(".nomedia" !in all)
    }

    @Test fun ranking() {
        val index = build()
        assertEquals("taxi receipt.jpg", names(index, "tax").first())
        assertEquals(listOf("taxi receipt.jpg", "2024_Tax_Return.pdf", "syntax-notes.txt"), names(index, "tax"))
        assertEquals("Résumé.docx", names(index, "resume").first())
        assertEquals("2024_Tax_Return.pdf", names(index, "taxreturn").first())
        assertEquals("2024_Tax_Return.pdf", names(index, "tax return").first())
        assertEquals("searchlight.zip", names(index, "searchlight").first())
        assertEquals("Projects", names(index, "projects").first())
        assertTrue(names(index, "q").isEmpty())
    }

    @Test fun pathsAndRoundTrip() {
        val index = build()
        val menu = index.entries.first { it.name == "menu.pdf" }
        assertEquals("Android/media/com.whatsapp/WhatsApp/Media/Doc", index.relativeDirOf(menu))
        assertTrue(index.pathOf(menu).endsWith("/Android/media/com.whatsapp/WhatsApp/Media/Doc/menu.pdf"))

        val file = File(tmp.root, "index.bin")
        index.write(file)
        val back = FileIndex.read(file)!!
        assertEquals(index.entries.size, back.entries.size)
        assertEquals(index.dirs, back.dirs)
        assertEquals(names(index, "tax"), names(back, "tax"))
    }
}
