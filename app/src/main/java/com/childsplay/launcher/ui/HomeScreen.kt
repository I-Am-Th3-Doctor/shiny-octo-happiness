package com.childsplay.launcher.ui

import android.Manifest
import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.data.AppEntry
import com.childsplay.launcher.data.HomeItem
import com.childsplay.launcher.data.Thing
import com.childsplay.launcher.things.ThingEditor
import com.childsplay.launcher.things.ThingPanel
import com.childsplay.launcher.things.ThingWidget
import com.childsplay.launcher.weather.HeroWeatherWidget
import com.childsplay.launcher.weather.OutlinedHeroText
import com.childsplay.launcher.weather.WeatherActivity
import com.childsplay.launcher.widgets.CalendarRepo
import com.childsplay.launcher.widgets.NoteColors
import com.childsplay.launcher.widgets.StickyNote
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private sealed interface EditorTarget {
    data object New : EditorTarget
    data class Existing(val id: String) : EditorTarget
}

private data class OpenPanel(val thingId: String, val origin: Rect)

/** Overall home screen state that the activity drives. */
class HomeSignals {
    /** Bumped when the user presses Home while already home. */
    var homePresses by mutableStateOf(0)
    /** Bumped on every onResume so permission checks re-run. */
    var resumes by mutableStateOf(0)
    var isDefaultHome by mutableStateOf(true)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: LauncherViewModel, signals: HomeSignals, onRequestDefaultHome: () -> Unit) {
    val context = LocalContext.current
    val apps by vm.apps.apps.collectAsState()
    val icons by vm.apps.icons.collectAsState()
    val home by vm.home.state.collectAsState()
    val weather by vm.weather.state.collectAsState()
    val skin by vm.weather.skin.collectAsState()
    val fahrenheit by vm.weather.fahrenheit.collectAsState()

    var panel by remember { mutableStateOf<OpenPanel?>(null) }
    var panelClosing by remember { mutableStateOf(false) }
    var editor by remember { mutableStateOf<EditorTarget?>(null) }
    var drawer by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    val calendarGranted = remember(signals.resumes) { CalendarRepo.hasPermission(context) }
    var calendarAsked by remember { mutableStateOf(0) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarAsked++ }
    val calendarNow = remember(signals.resumes, calendarAsked) { CalendarRepo.hasPermission(context) } || calendarGranted

    val byKey = remember(apps) { apps.associateBy { it.key } }
    fun appsOf(t: Thing): List<AppEntry> = t.appKeys.mapNotNull { byKey[it] }

    // Pressing Home while home tidies everything away and scrolls to the top.
    LaunchedEffect(signals.homePresses) {
        if (signals.homePresses == 0) return@LaunchedEffect
        editor = null
        drawer = false
        if (panel != null) panelClosing = true
        gridState.animateScrollToItem(0)
    }

    val room = Rooms.get(home.room)
    val launch: (AppEntry, Rect) -> Unit = { app, bounds ->
        vm.apps.launch(app, bounds.toAndroidRectOrNull())
    }

    Box(Modifier.fillMaxSize()) {
        RoomBackdrop(room, Modifier.fillMaxSize().graphicsLayer())

        val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = gridState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = statusTop + 8.dp, bottom = navBottom + 110.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "clock", span = { GridItemSpan(4) }) { ClockHeader() }
            if (!signals.isDefaultHome) {
                item(key = "default", span = { GridItemSpan(4) }) {
                    Text(
                        "Make Child's Play your home screen →",
                        fontFamily = Chunky, fontSize = 17.sp, color = Toy.Ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toyBlock(Toy.Yellow, corner = 20.dp, shadow = 4.dp)
                            .clickable(onClick = onRequestDefaultHome)
                            .padding(14.dp),
                    )
                }
            }
            items(home.items, key = { it.id }, span = { GridItemSpan(if (it is HomeItem.Weather) 4 else 2) }) { item ->
                Box(Modifier.animateItem()) {
                    when (item) {
                        is HomeItem.Weather -> ItemWithMenu(
                            onClick = {
                                context.startActivity(
                                    Intent(context, WeatherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            menu = { close ->
                                MoveItems(item, vm, close)
                                DropdownMenuItem(text = { Text("Take off home") }, onClick = { close(); vm.home.setWidgetVisible(item, false) })
                            },
                        ) {
                            HeroWeatherWidget(
                                weather, skin, fahrenheit,
                                Modifier.fillMaxWidth().aspectRatio(2.1f).toyBlock(skin.card, corner = 30.dp),
                            )
                        }

                        is HomeItem.StickyNote -> ItemWithMenu(
                            onClick = {
                                if (calendarNow) CalendarRepo.openToday(context)
                                else calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                            },
                            menu = { close ->
                                DropdownMenuItem(
                                    text = { Text("New note colour") },
                                    onClick = { close(); vm.home.setNoteColor((home.noteColor + 1) % NoteColors.size) },
                                )
                                MoveItems(item, vm, close)
                                DropdownMenuItem(text = { Text("Take off home") }, onClick = { close(); vm.home.setWidgetVisible(item, false) })
                            },
                        ) {
                            StickyNote(home.noteColor, calendarNow, Modifier.fillMaxWidth().aspectRatio(1f))
                        }

                        is HomeItem.ThingItem -> {
                            val thing = home.things[item.thingId]
                            if (thing != null) {
                                var menu by remember { mutableStateOf(false) }
                                val haptics = LocalHapticFeedback.current
                                Box {
                                    ThingWidget(
                                        thing = thing,
                                        apps = appsOf(thing),
                                        icons = icons,
                                        onOpen = { r -> panelClosing = false; panel = OpenPanel(thing.id, r) },
                                        onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    )
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Change ${thing.name}") }, onClick = { menu = false; editor = EditorTarget.Existing(thing.id) })
                                        MoveItems(item, vm) { menu = false }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "add", span = { GridItemSpan(2) }) {
                Column(
                    Modifier
                        .padding(10.dp)
                        .aspectRatio(1f)
                        .toyBlock(Color.White, corner = 30.dp, shadow = 5.dp)
                        .clickable { editor = EditorTarget.New },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("+", fontFamily = Chunky, fontSize = 64.sp, color = Toy.Pink)
                    Text("New thing", fontFamily = Chunky, fontSize = 18.sp, color = Toy.Ink)
                }
            }
        }

        Dock(
            showWeatherAdd = home.items.none { it is HomeItem.Weather },
            showNoteAdd = home.items.none { it is HomeItem.StickyNote },
            onAllApps = { drawer = true },
            onNewThing = { editor = EditorTarget.New },
            onAddWidget = { vm.home.setWidgetVisible(it, true) },
            onNextRoom = { vm.home.setRoom(home.room + 1) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        AnimatedVisibility(
            visible = drawer,
            enter = slideInVertically(spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            AppDrawer(
                apps = apps,
                icons = icons,
                things = home.items.mapNotNull { (it as? HomeItem.ThingItem)?.let { t -> home.things[t.thingId] } },
                onLaunch = { app, r -> launch(app, r); drawer = false },
                onPutInThing = { app, t -> vm.home.addAppToThing(t.id, app.key) },
                onAppInfo = vm.apps::openAppInfo,
                onUninstall = vm.apps::uninstall,
                onClose = { drawer = false },
            )
        }

        panel?.let { p ->
            val thing = home.things[p.thingId]
            if (thing == null) {
                LaunchedEffect(p) { panel = null }
            } else {
                ThingPanel(
                    thing = thing,
                    apps = appsOf(thing),
                    icons = icons,
                    origin = p.origin,
                    closeRequested = panelClosing,
                    onLaunch = { app, r -> launch(app, r); panel = null },
                    onEdit = { editor = EditorTarget.Existing(thing.id); panel = null },
                    onRemoveApp = { vm.home.removeAppFromThing(thing.id, it.key) },
                    onAppInfo = vm.apps::openAppInfo,
                    onClosed = { panel = null; panelClosing = false },
                )
            }
        }

        editor?.let { target ->
            val existing = (target as? EditorTarget.Existing)?.let { home.things[it.id] }
            ThingEditor(
                existing = existing,
                allApps = apps,
                icons = icons,
                onSave = { vm.home.upsertThing(it); editor = null },
                onDelete = { vm.home.deleteThing(it.id); editor = null },
                onDismiss = { editor = null },
            )
        }
    }

    // As a launcher, Back on the bare home screen does nothing (overlays handle their own Back).
    BackHandler(enabled = panel == null && editor == null && !drawer) {}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemWithMenu(
    onClick: () -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
    content: @Composable () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Box(
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); open = true },
        )
    ) {
        content()
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { menu { open = false } }
    }
}

@Composable
private fun MoveItems(item: HomeItem, vm: LauncherViewModel, close: () -> Unit) {
    DropdownMenuItem(text = { Text("Move earlier") }, onClick = { close(); vm.home.move(item.id, -1) })
    DropdownMenuItem(text = { Text("Move later") }, onClick = { close(); vm.home.move(item.id, +1) })
}

@Composable
private fun ClockHeader() {
    val context = LocalContext.current
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay((60 - value.second) * 1000L - value.nano / 1_000_000L + 50L)
        }
    }
    val pattern = if (DateFormat.is24HourFormat(context)) "H:mm" else "h:mm"
    val time = remember(now, pattern) { DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(now) }
    val date = remember(now.toLocalDate()) { DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()).format(now) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                runCatching {
                    context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
            .padding(start = 6.dp, top = 6.dp, bottom = 2.dp),
    ) {
        OutlinedHeroText(time, 84.sp, Color.White, outline = 14f)
        Text(
            date,
            fontFamily = Chunky, fontSize = 20.sp, color = Toy.Ink,
            modifier = Modifier.toyBlock(Toy.Yellow, corner = 50.dp, shadow = 4.dp).padding(horizontal = 14.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Dock(
    showWeatherAdd: Boolean,
    showNoteAdd: Boolean,
    onAllApps: () -> Unit,
    onNewThing: () -> Unit,
    onAddWidget: (HomeItem) -> Unit,
    onNextRoom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var addMenu by remember { mutableStateOf(false) }
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            RoundToyButton("+", Toy.Yellow) { addMenu = true }
            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                DropdownMenuItem(text = { Text("New thing") }, onClick = { addMenu = false; onNewThing() })
                if (showWeatherAdd || showNoteAdd) HorizontalDivider()
                if (showWeatherAdd) DropdownMenuItem(text = { Text("Hero Weather widget") }, onClick = { addMenu = false; onAddWidget(HomeItem.Weather) })
                if (showNoteAdd) DropdownMenuItem(text = { Text("Sticky note") }, onClick = { addMenu = false; onAddWidget(HomeItem.StickyNote) })
            }
        }
        Box(
            Modifier
                .height(64.dp)
                .toyBlock(Toy.Pink, corner = 50.dp, shadow = 6.dp)
                .clickable(onClick = onAllApps)
                .padding(horizontal = 30.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("All apps", fontFamily = Chunky, fontSize = 26.sp, color = Color.White)
        }
        RoundToyButton("✱", Toy.Sky, onClick = onNextRoom)
    }
}

@Composable
private fun RoundToyButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(58.dp)
            .toyBlock(color, corner = 29.dp, shadow = 5.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Chunky, fontSize = 30.sp, color = Toy.onColor(color))
    }
}

private fun Rect.toAndroidRectOrNull(): android.graphics.Rect? =
    if (this == Rect.Zero || width <= 0f) null
    else android.graphics.Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
