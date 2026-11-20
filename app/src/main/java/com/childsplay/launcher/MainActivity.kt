package com.childsplay.launcher

import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import com.childsplay.launcher.ui.HomeScreen
import com.childsplay.launcher.ui.HomeSignals
import com.childsplay.launcher.ui.LauncherViewModel
import com.childsplay.launcher.ui.Rooms
import com.childsplay.launcher.ui.ToyTheme

/** Child's Play home screen. */
class MainActivity : ComponentActivity() {
    private val vm: LauncherViewModel by viewModels()
    private val signals = HomeSignals()
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        signals.isDefaultHome = isDefaultHome()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            ToyTheme {
                val home by vm.home.state.collectAsState()
                val room = Rooms.get(home.room)
                LaunchedEffect(room.darkIcons) {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = room.darkIcons
                        isAppearanceLightNavigationBars = room.darkIcons
                    }
                }
                HomeScreen(vm, signals, onRequestDefaultHome = ::requestDefaultHome)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        signals.resumes++
        signals.isDefaultHome = isDefaultHome()
        vm.weather.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            signals.homePresses++
        }
    }

    private fun isDefaultHome(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME)) return rm.isRoleHeld(RoleManager.ROLE_HOME)
        }
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == packageName
    }

    private fun requestDefaultHome() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rm = getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                runCatching { roleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME)) }.onSuccess { return }
            }
        }
        runCatching { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }
}
