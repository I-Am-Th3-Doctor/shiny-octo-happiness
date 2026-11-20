package com.something.launcher.ui

import android.Manifest
import android.content.Intent
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.something.launcher.data.AppEntry
import com.something.launcher.data.HomeItem
import com.something.launcher.weather.WeatherActivity
import com.something.launcher.weather.WeatherWidget
import com.something.launcher.widgets.CalendarRepo
import com.something.launcher.widgets.StickyNote
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Signals the activity feeds into the home screen. */
class HomeSignals {
    var homePresses by mutableIntStateOf(0)
    var resumes by mutableIntStateOf(0)
    var isDefaultHome by mutableStateOf(true)
}

@Composable
fun HomeScreen(vm: LauncherViewModel, signals: HomeSignals, onRequestDefaultHome: () -> Unit) {
    val context = LocalContext.current
    val apps by vm.apps.apps.collectAsState()
    val icons by vm.apps.icons.collectAsState()
    val home by vm.home.state.collectAsState()
    val weather by vm.weather.state.collectAsState()
    val fahrenheit by vm.weather.fahrenheit.collectAsState()

    var drawer by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    var calendarAsked by remember { mutableIntStateOf(0) }
    val calendarPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { calendarAsked++ }
    val calendarGranted = remember(signals.resumes, calendarAsked) { CalendarRepo.hasPermission(context) }
    val byKey = remember(apps) { apps.associateBy { it.key } }

    LaunchedEffect(signals.homePresses) {
        if (signals.homePresses == 0) return@LaunchedEffect
        drawer = false
        gridState.animateScrollToItem(0)
    }

    val launch: (AppEntry, Rect) -> Unit = { app, r ->
        vm.apps.launch(app, if (r == Rect.Zero) null else android.graphics.Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt()))
    }

    // Swipe up past the end of the home grid to open the app drawer.
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    val openDrawer by rememberUpdatedState { drawer = true }
    val swipeUp = remember {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y < 0f) {
                    pulled += -available.y
                    if (pulled > threshold) { pulled = 0f; openDrawer() }
                } else if (available.y > 0f) pulled = 0f
                return Offset.Zero
            }
            override suspend fun onPreFling(available: Velocity): Velocity { pulled = 0f; return Velocity.Zero }
        }
    }

    Box(Modifier.fillMaxSize().dotGrid()) {
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = gridState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 20.dp, bottom = bottom + 110.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            modifier = Modifier.fillMaxSize().nestedScroll(swipeUp),
        ) {
            item(key = "clock", span = { GridItemSpan(4) }) { Clock() }
            if (!signals.isDefaultHome) {
                item(key = "default", span = { GridItemSpan(4) }) {
                    Row(
                        Modifier.fillMaxWidth().dotFrame(Ink.Red, corner = 18.dp).clickable(onClick = onRequestDefaultHome).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        DotText("Set as home screen", 15.sp, modifier = Modifier.weight(1f))
                        DotText("→", 18.sp, color = Ink.Red)
                    }
                }
            }
            items(home.items, key = { it.id }, span = { GridItemSpan(if (it is HomeItem.App) 1 else 2) }) { item ->
                Box(Modifier.animateItem()) {
                    when (item) {
                        is HomeItem.Weather -> WidgetWithMenu(
                            onClick = {
                                context.startActivity(Intent(context, WeatherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            },
                            menu = { close ->
                                DotMenuItem("Move earlier") { close(); vm.home.move(item.id, -1) }
                                DotMenuItem("Move later") { close(); vm.home.move(item.id, 1) }
                                DotMenuItem("Remove widget", red = true) { close(); vm.home.setWidgetVisible(item, false) }
                            },
                        ) { WeatherWidget(weather, fahrenheit, Modifier.fillMaxWidth().aspectRatio(1f)) }

                        is HomeItem.StickyNote -> WidgetWithMenu(
                            onClick = {
                                if (calendarGranted) CalendarRepo.openToday(context)
                                else calendarPermission.launch(Manifest.permission.READ_CALENDAR)
                            },
                            menu = { close ->
                                DotMenuItem("Move earlier") { close(); vm.home.move(item.id, -1) }
                                DotMenuItem("Move later") { close(); vm.home.move(item.id, 1) }
                                DotMenuItem("Remove widget", red = true) { close(); vm.home.setWidgetVisible(item, false) }
                            },
                        ) { StickyNote(calendarGranted, Modifier.fillMaxWidth().aspectRatio(1f)) }

                        is HomeItem.App -> {
                            val app = byKey[item.key]
                            if (app != null) {
                                AppCell(app, icons[app.key], launch, menu = { close ->
                                    DotMenuItem("Move earlier") { close(); vm.home.move(item.id, -1) }
                                    DotMenuItem("Move later") { close(); vm.home.move(item.id, 1) }
                                    DotMenuItem("App info") { close(); vm.apps.openAppInfo(app) }
                                    DotMenuItem("Unpin", red = true) { close(); vm.home.unpin(app.key) }
                                })
                            }
                        }
                    }
                }
            }
        }

        BottomBar(
            showWeatherAdd = home.items.none { it is HomeItem.Weather },
            showNoteAdd = home.items.none { it is HomeItem.StickyNote },
            onAllApps = { drawer = true },
            onAdd = { vm.home.setWidgetVisible(it, true) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        AnimatedVisibility(
            visible = drawer,
            enter = slideInVertically(tween(260)) { it / 6 } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(200)) { it / 6 } + fadeOut(tween(160)),
        ) {
            AppDrawer(
                apps = apps,
                icons = icons,
                pinned = home.pinned,
                onLaunch = { app, r -> launch(app, r); drawer = false },
                onPin = { vm.home.pin(it.key) },
                onUnpin = { vm.home.unpin(it.key) },
                onAppInfo = vm.apps::openAppInfo,
                onUninstall = vm.apps::uninstall,
                onClose = { drawer = false },
            )
        }
    }

    // Back on the bare home screen does nothing.
    BackHandler(enabled = !drawer) {}
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetWithMenu(
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
        DotMenu(expanded = open, onDismiss = { open = false }) { menu { open = false } }
    }
}

@Composable
private fun Clock() {
    val context = LocalContext.current
    val now by produceState(LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            delay((60 - value.second) * 1000L - value.nano / 1_000_000L + 50L)
        }
    }
    val is24 = DateFormat.is24HourFormat(context)
    val time = remember(now, is24) { DateTimeFormatter.ofPattern(if (is24) "HH:mm" else "h:mm", Locale.getDefault()).format(now) }
    val date = remember(now.toLocalDate()) { DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()).format(now) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable {
                runCatching { context.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            .padding(top = 8.dp, bottom = 8.dp),
    ) {
        // Hours and minutes in dot type; the colon is two red dots.
        val (hh, mm) = time.split(":").let { it[0] to it[1] }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotText(hh, 92.sp, letterSpacing = 0.sp)
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(2) { Box(Modifier.size(11.dp).background(Ink.Red, CircleShape)) }
            }
            DotText(mm, 92.sp, letterSpacing = 0.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotText(date, 18.sp, color = Ink.Dim)
            if (!is24) {
                Spacer(Modifier.width(10.dp))
                DotText(if (now.hour < 12) "AM" else "PM", 18.sp, color = Ink.Red)
            }
        }
    }
}

@Composable
private fun BottomBar(
    showWeatherAdd: Boolean,
    showNoteAdd: Boolean,
    onAllApps: () -> Unit,
    onAdd: (HomeItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.navigationBarsPadding().padding(bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showWeatherAdd || showNoteAdd) {
            Box {
                Box(
                    Modifier.size(52.dp).dotFrame(Ink.White, corner = 26.dp).clickable { menu = true },
                    contentAlignment = Alignment.Center,
                ) { DotText("+", 24.sp) }
                DotMenu(expanded = menu, onDismiss = { menu = false }) {
                    if (showWeatherAdd) DotMenuItem("Add weather") { menu = false; onAdd(HomeItem.Weather) }
                    if (showNoteAdd) DotMenuItem("Add sticky note") { menu = false; onAdd(HomeItem.StickyNote) }
                }
            }
        }
        Row(
            Modifier.height(52.dp).dotFrame(Ink.White, corner = 26.dp).clickable(onClick = onAllApps).padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DotText("All apps", 17.sp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(7.dp).background(Ink.Red, CircleShape))
        }
    }
}
