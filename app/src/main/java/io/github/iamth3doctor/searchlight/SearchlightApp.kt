package io.github.iamth3doctor.searchlight

import android.app.Application
import android.content.Context
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.contacts.ContactRepository
import io.github.iamth3doctor.searchlight.data.Prefs
import io.github.iamth3doctor.searchlight.files.FileSearch

class SearchlightApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var contacts: ContactRepository
        private set
    lateinit var files: FileSearch
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        apps = AppRepository(this, prefs)
        contacts = ContactRepository(this)
        files = FileSearch(this)
        apps.start()
        contacts.start()
        files.start()
    }
}

val Context.searchlight: SearchlightApp get() = applicationContext as SearchlightApp
