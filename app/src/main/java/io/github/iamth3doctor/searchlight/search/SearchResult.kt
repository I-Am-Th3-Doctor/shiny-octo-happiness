package io.github.iamth3doctor.searchlight.search

import io.github.iamth3doctor.searchlight.apps.AppEntry
import io.github.iamth3doctor.searchlight.calendar.EventEntry
import io.github.iamth3doctor.searchlight.contacts.ContactEntry
import io.github.iamth3doctor.searchlight.data.WebTarget
import io.github.iamth3doctor.searchlight.files.FileEntry
import io.github.iamth3doctor.searchlight.files.FileIndex
import io.github.iamth3doctor.searchlight.media.PhotoEntry
import io.github.iamth3doctor.searchlight.media.SongEntry
import io.github.iamth3doctor.searchlight.messages.MessageEntry

sealed interface SearchResult {
    class Calc(val expression: String, val value: String) : SearchResult
    /** A unit or currency conversion: [value] shown big (and copied on tap), [detail] under it. */
    class Convert(val value: String, val detail: String, val copyText: String) : SearchResult
    /** "y lofi": search [match]'s app for its query. */
    class Shortcut(val match: PrefixShortcuts.Match) : SearchResult
    /** While a shortcut key is typed alone: what typing on will search. */
    class ShortcutHint(val target: PrefixShortcuts.Target) : SearchResult
    class Event(val entry: EventEntry) : SearchResult
    class App(val entry: AppEntry) : SearchResult
    class Contact(val entry: ContactEntry) : SearchResult
    class Song(val entry: SongEntry) : SearchResult
    class Photo(val entry: PhotoEntry) : SearchResult
    /** [matchIndex] is where the query matched in the body, or -1 if it matched the sender. */
    class Message(val entry: MessageEntry, val sender: String, val matchIndex: Int) : SearchResult
    class File(val index: FileIndex, val entry: FileEntry) : SearchResult
    class Web(val query: String, val engine: WebTarget) : SearchResult
    /** "Search contacts, files & more" — shown until access is granted or the hint is dismissed. */
    data object AccessHint : SearchResult
}
