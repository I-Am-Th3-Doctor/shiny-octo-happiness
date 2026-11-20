package com.something.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.something.launcher.data.AppEntry

/** Every launchable app, A to Z, with search. Long-press to pin, see info, or uninstall. */
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    icons: Map<String, ImageBitmap>,
    pinned: Set<String>,
    onLaunch: (AppEntry, Rect) -> Unit,
    onPin: (AppEntry) -> Unit,
    onUnpin: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onUninstall: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    BackHandler { if (query.isNotEmpty()) query = "" else onClose() }
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps
        else apps.filter { it.label.contains(q, ignoreCase = true) }
            .sortedBy { if (it.label.startsWith(q, ignoreCase = true)) 0 else 1 }
    }

    Column(
        Modifier
            .fillMaxSize()
            .dotGrid()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            DotText("Apps", 30.sp, modifier = Modifier.weight(1f))
            DotText("${apps.size}", 14.sp, color = Ink.Red, modifier = Modifier.padding(end = 14.dp))
            DotText("Close", 14.sp, color = Ink.Dim, modifier = Modifier.clickable(onClick = onClose).padding(8.dp))
        }
        DotField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search",
            imeAction = ImeAction.Go,
            onImeAction = {
                filtered.firstOrNull()?.let { onLaunch(it, Rect.Zero) }
                focus.clearFocus()
            },
        )
        Spacer(Modifier.height(12.dp))
        if (filtered.isEmpty()) {
            DotText(if (apps.isEmpty()) "Loading" else "No match", 18.sp, color = Ink.Dim, modifier = Modifier.padding(top = 40.dp))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.weight(1f).navigationBarsPadding(),
        ) {
            items(filtered, key = { it.key }) { app ->
                AppCell(app, icons[app.key], onLaunch, menu = { close ->
                    if (app.key in pinned) DotMenuItem("Unpin from home") { close(); onUnpin(app) }
                    else DotMenuItem("Pin to home") { close(); onPin(app) }
                    DotMenuItem("App info") { close(); onAppInfo(app) }
                    DotMenuItem("Uninstall", red = true) { close(); onUninstall(app) }
                })
            }
        }
    }
}
