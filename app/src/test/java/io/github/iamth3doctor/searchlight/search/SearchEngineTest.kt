package io.github.iamth3doctor.searchlight.search

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {
    @Test fun contactsNeedTwoCharacters() {
        assertFalse(SearchEngine.searchesContacts("a"))
        assertFalse(SearchEngine.searchesContacts(" a "))
        assertTrue(SearchEngine.searchesContacts("al"))
        assertTrue(SearchEngine.searchesContacts("555"))
    }
}
