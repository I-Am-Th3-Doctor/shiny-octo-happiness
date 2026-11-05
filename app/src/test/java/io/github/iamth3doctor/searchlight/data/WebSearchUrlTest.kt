package io.github.iamth3doctor.searchlight.data

import io.github.iamth3doctor.searchlight.data.WebSearchUrl.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WebSearchUrlTest {
    @Test fun aValidCustomEngineSaves() {
        assertNull(WebSearchUrl.check("My Search", "https://search.example.com/?q=%s"))
        assertNull(WebSearchUrl.check("X", "HTTPS://a.example/%s/results"))
    }

    @Test fun invalidOnesSayWhy() {
        assertEquals(Problem.NAME_MISSING, WebSearchUrl.check("  ", "https://a.example/?q=%s"))
        assertEquals(Problem.NOT_HTTPS, WebSearchUrl.check("X", "http://a.example/?q=%s"))
        assertEquals(Problem.NOT_HTTPS, WebSearchUrl.check("X", "a.example/?q=%s"))
        assertEquals(Problem.NOT_HTTPS, WebSearchUrl.check("X", "https://"))
        assertEquals(Problem.NO_PLACEHOLDER, WebSearchUrl.check("X", "https://a.example/?q="))
        assertEquals(Problem.MANY_PLACEHOLDERS, WebSearchUrl.check("X", "https://a.example/?q=%s&r=%s"))
    }

    @Test fun theQueryGoesWhereThePlaceholderIs() {
        assertEquals("https://a.example/?q=lofi+beats&x=%20", WebSearchUrl.build("https://a.example/?q=%s&x=%20", "lofi beats"))
        assertEquals("https://a.example/c%26a", WebSearchUrl.build(" https://a.example/%s ", "c&a"))
    }

    @Test fun everyPresetSubstitutesTheQuery() {
        val expected = mapOf(
            WebEngine.GOOGLE to "https://www.google.com/search?q=lofi+beats",
            WebEngine.DUCKDUCKGO to "https://duckduckgo.com/?q=lofi+beats",
            WebEngine.BRAVE to "https://search.brave.com/search?q=lofi+beats",
            WebEngine.BING to "https://www.bing.com/search?q=lofi+beats",
            WebEngine.STARTPAGE to "https://www.startpage.com/sp/search?query=lofi+beats",
            WebEngine.ECOSIA to "https://www.ecosia.org/search?q=lofi+beats",
            WebEngine.QWANT to "https://www.qwant.com/?q=lofi+beats",
            WebEngine.YAHOO to "https://search.yahoo.com/search?p=lofi+beats",
            WebEngine.MOJEEK to "https://www.mojeek.com/search?q=lofi+beats",
        )
        for ((engine, url) in expected) assertEquals(engine.name, url, engine.url("lofi beats"))
    }

    @Test fun theFourOriginalsKeepTheirPlaceAndNames() {
        assertEquals(listOf("Google", "DuckDuckGo", "Brave", "Bing"), WebEngine.entries.take(4).map { it.label })
        assertEquals(WebEngine.CUSTOM, WebEngine.entries.last())
    }
}
