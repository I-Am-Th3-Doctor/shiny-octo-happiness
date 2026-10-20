package io.github.iamth3doctor.searchlight

import android.app.Application
import android.content.Context
import io.github.iamth3doctor.searchlight.apps.AppRepository
import io.github.iamth3doctor.searchlight.data.Prefs

class SearchlightApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var apps: AppRepository
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        apps = AppRepository(this, prefs)
        apps.start()
    }
}

val Context.searchlight: SearchlightApp get() = applicationContext as SearchlightApp
