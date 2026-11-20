package com.something.launcher.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.something.launcher.data.AppRepository
import com.something.launcher.data.HomeStore
import com.something.launcher.weather.WeatherStore
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class LauncherViewModel(app: Application) : AndroidViewModel(app) {
    val apps = AppRepository.get(app)
    val home = HomeStore.get(app)
    val weather = WeatherStore.get(app)

    init {
        // First run: once the app list is in, set up the grid with widgets and essentials.
        viewModelScope.launch {
            if (home.state.value.seeded) return@launch
            val list = combine(apps.loaded, apps.apps) { loaded, list -> if (loaded) list else null }.first { it != null }!!
            if (!home.state.value.seeded) {
                val seeded = HomeStore.seed(getApplication(), list)
                home.update { seeded }
            }
        }
    }
}
