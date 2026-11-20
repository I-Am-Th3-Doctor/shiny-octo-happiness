package com.childsplay.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.data.AppEntry
import com.childsplay.launcher.data.Thing
import com.childsplay.launcher.things.ToyTextField

/**
 * The toy box: every launchable app, A to Z, with search. Long-press an app to drop it into a
 * thing, see its info, or uninstall it.
 */
@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    icons: Map<String, ImageBitmap>,
    things: List<Thing>,
    onLaunch: (AppEntry, Rect) -> Unit,
    onPutInThing: (AppEntry, Thing) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onUninstall: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    BackHandler {
        if (query.isNotEmpty()) query = "" else onClose()
    }
    val filtered = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps
        else apps.filter { it.label.contains(q, ignoreCase = true) }
            .sortedBy { if (it.label.startsWith(q, ignoreCase = true)) 0 else 1 }
    }
    val gridState = rememberLazyGridState()

    Column(
        Modifier
            .fillMaxSize()
            .background(Toy.Blue)
            .statusBarsPadding()
            .imePadding()
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Toy Box", fontFamily = Chunky, fontSize = 38.sp, color = Toy.Yellow, modifier = Modifier.weight(1f))
            Text(
                "Close",
                fontFamily = Chunky, fontSize = 17.sp, color = Toy.Ink,
                modifier = Modifier
                    .toyBlock(Toy.Yellow, corner = 50.dp, shadow = 3.dp)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        ToyTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Find an app…",
            imeAction = ImeAction.Go,
            onImeAction = {
                filtered.firstOrNull()?.let { onLaunch(it, Rect.Zero) }
                focus.clearFocus()
            },
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) {
            Text(
                if (apps.isEmpty()) "Unpacking your toys…" else "Nothing called \"$query\"",
                fontFamily = Chunky, fontSize = 22.sp, color = Toy.Yellow, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(84.dp),
            state = gridState,
            contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f).navigationBarsPadding(),
        ) {
            items(filtered, key = { it.key }) { app ->
                DrawerApp(app, icons[app.key], things, onLaunch, onPutInThing, onAppInfo, onUninstall)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerApp(
    app: AppEntry,
    icon: ImageBitmap?,
    things: List<Thing>,
    onLaunch: (AppEntry, Rect) -> Unit,
    onPutInThing: (AppEntry, Thing) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onUninstall: (AppEntry) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val haptics = LocalHapticFeedback.current
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .combinedClickable(
                    onClick = { onLaunch(app, bounds) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app, icon, 56.dp)
            Spacer(Modifier.height(5.dp))
            Text(
                app.label,
                fontFamily = Bubbly, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false; picking = false }) {
            if (!picking) {
                if (things.isNotEmpty()) DropdownMenuItem(text = { Text("Put in a thing…") }, onClick = { picking = true })
                DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; onAppInfo(app) })
                DropdownMenuItem(text = { Text("Uninstall") }, onClick = { menu = false; onUninstall(app) })
            } else {
                Text("Put ${app.label} in…", fontFamily = Chunky, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
                HorizontalDivider()
                things.forEach { t ->
                    val already = app.key in t.appKeys
                    DropdownMenuItem(
                        text = { Text(if (already) "${t.name} ✓" else t.name) },
                        enabled = !already,
                        onClick = { menu = false; picking = false; onPutInThing(app, t) },
                    )
                }
            }
        }
    }
}
