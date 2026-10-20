package io.github.iamth3doctor.searchlight.search

import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.contacts.ContactEntry
import io.github.iamth3doctor.searchlight.data.WebEngine
import io.github.iamth3doctor.searchlight.files.FileEntry
import io.github.iamth3doctor.searchlight.files.FileIndex

sealed interface SearchResult {
    class Calc(val expression: String, val value: String) : SearchResult
    class App(val entry: AppEntry) : SearchResult
    class Contact(val entry: ContactEntry) : SearchResult
    class File(val index: FileIndex, val entry: FileEntry) : SearchResult
    class Web(val query: String, val engine: WebEngine) : SearchResult
    /** "Allow contacts & files to search them too" — shown until granted or dismissed. */
    data object AccessHint : SearchResult
}
