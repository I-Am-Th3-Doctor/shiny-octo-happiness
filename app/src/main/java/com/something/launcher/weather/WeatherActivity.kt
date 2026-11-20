package com.something.launcher.weather

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.something.launcher.ui.SomethingTheme

/** The dot-matrix Weather app. */
class WeatherActivity : ComponentActivity() {
    private val store by lazy { WeatherStore.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent { SomethingTheme { WeatherScreen(store) } }
    }

    override fun onResume() {
        super.onResume()
        store.refresh()
    }
}
