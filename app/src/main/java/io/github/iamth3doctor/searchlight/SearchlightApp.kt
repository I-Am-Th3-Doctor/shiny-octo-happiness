package io.github.iamth3doctor.searchlight

import android.app.Application
import android.content.Context
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.contacts.ContactRepository
import io.github.iamth3doctor.searchlight.data.Prefs
import io.github.iamth3doctor.searchlight.files.FileSearch
import io.github.iamth3doctor.searchlight.media.MediaRepository
import io.github.iamth3doctor.searchlight.media.Thumbnails
import io.github.iamth3doctor.searchlight.messages.MessageRepository
import io.github.iamth3doctor.searchlight.search.BackgroundSearch
import io.github.iamth3doctor.searchlight.weather.WeatherRepository

class SearchlightApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var contacts: ContactRepository
        private set
    lateinit var files: FileSearch
        private set
    lateinit var messages: MessageRepository
        private set
    lateinit var media: MediaRepository
        private set
    lateinit var thumbnails: Thumbnails
        private set
    lateinit var background: BackgroundSearch
        private set
    lateinit var weather: WeatherRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        apps = AppRepository(this, prefs)
        contacts = ContactRepository(this)
        files = FileSearch(this)
        messages = MessageRepository(this, contacts)
        media = MediaRepository(this)
        thumbnails = Thumbnails(this)
        background = BackgroundSearch(files, media, messages)
        weather = WeatherRepository(this, prefs)
        apps.start()
        startSources()
        weather.start()
    }

    /**
     * Starts every optional search source that now has permission. Cheap and idempotent:
     * called at startup and whenever we come back from a permissions screen.
     */
    fun startSources() {
        contacts.start()
        files.start()
        messages.start()
        media.start()
    }
}

val Context.searchlight: SearchlightApp get() = applicationContext as SearchlightApp
