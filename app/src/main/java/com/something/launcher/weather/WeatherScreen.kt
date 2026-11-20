package com.something.launcher.weather

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.something.launcher.ui.DotButton
import com.something.launcher.ui.DotRule
import com.something.launcher.ui.DotText
import com.something.launcher.ui.Glyphs
import com.something.launcher.ui.Ink
import com.something.launcher.ui.MonoText
import com.something.launcher.ui.PixelGlyph
import com.something.launcher.ui.dotFrame
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val hourFmt = DateTimeFormatter.ofPattern("ha", Locale.getDefault())
private val clockFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val alertFmt = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())

fun formatEnds(iso: String?): String? = iso?.let {
    runCatching { alertFmt.format(OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())) }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(store: WeatherStore) {
    val st by store.state.collectAsState()
    val fahrenheit by store.fahrenheit.collectAsState()
    val manual by store.manualPlace.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }
    val forecast = st.forecast

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        store.setManualPlace(null)
    }
    val useMyLocation = {
        picking = false
        if (store.hasLocationPermission()) store.setManualPlace(null)
        else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    val pull = rememberPullToRefreshState()

    Box(Modifier.fillMaxSize().background(Ink.Black)) {
        PullToRefreshBox(
            isRefreshing = st.loading,
            onRefresh = { store.refresh(force = true) },
            state = pull,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull, isRefreshing = st.loading, modifier = Modifier.align(Alignment.TopCenter).systemBarsPadding(),
                    containerColor = Ink.Black, color = Ink.Red,
                )
            },
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable { picking = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (manual == null) {
                            Box(Modifier.size(8.dp).background(Ink.Red, androidx.compose.foundation.shape.CircleShape))
                            Spacer(Modifier.width(8.dp))
                        }
                        DotText(forecast?.place?.name ?: manual?.name ?: "Weather", 20.sp)
                    }
                    DotText(
                        if (fahrenheit) "°F" else "°C", 16.sp,
                        modifier = Modifier
                            .dotFrame(Ink.White, corner = 16.dp)
                            .clickable { store.setFahrenheit(!fahrenheit) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                forecast?.alerts?.forEach { AlertBlock(it) }

                if (forecast == null) {
                    Spacer(Modifier.height(60.dp))
                    PixelGlyph(Glyphs.PartlyDay, 120.dp, unlit = Ink.Faint)
                    Spacer(Modifier.height(24.dp))
                    DotText(if (st.loading) "Reading the sky" else "Where are you?", 30.sp, maxLines = 2)
                    Spacer(Modifier.height(10.dp))
                    MonoText(st.error ?: "Share an approximate location, or pick a city.", size = 14.sp)
                    Spacer(Modifier.height(24.dp))
                    DotButton("Use my location", Modifier.fillMaxWidth(), accent = true, onClick = useMyLocation)
                    Spacer(Modifier.height(12.dp))
                    DotButton("Pick a city", Modifier.fillMaxWidth()) { picking = true }
                    Spacer(Modifier.height(300.dp))
                } else {
                    Hero(forecast, fahrenheit)
                    st.error?.let {
                        MonoText(it, color = Ink.Red, size = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
                    }
                    Section("48 hours", hourlyTag(forecast))
                    HourlyStrip(forecast, fahrenheit)
                    Section("7 days", "HRRR → ECMWF")
                    Daily(forecast, fahrenheit)
                    Section("Now", forecast.current.source.label)
                    Details(forecast, fahrenheit)
                    Spacer(Modifier.height(28.dp))
                    DotRule()
                    Spacer(Modifier.height(10.dp))
                    MonoText(
                        "Open-Meteo: HRRR, hours 0–48 · ECMWF IFS, days 3–7\nAlerts: National Weather Service\nUpdated " +
                            clockFmt.format(Instant.ofEpochMilli(forecast.fetchedAt).atZone(ZoneId.systemDefault())),
                        size = 11.sp,
                    )
                    Spacer(Modifier.height(32.dp))
                }
            }
        }

        if (picking) {
            PlacePicker(
                onPick = { picking = false; store.setManualPlace(it) },
                onUseLocation = useMyLocation,
                onDismiss = { picking = false },
            )
        }
    }
}

private fun hourlyTag(f: Forecast): String {
    val sources = f.hours.map { it.source }.toSet()
    return when {
        sources == setOf(Source.HRRR) -> "HRRR"
        Source.HRRR in sources -> "HRRR + ECMWF"
        else -> "ECMWF · no HRRR here"
    }
}

@Composable
private fun Hero(f: Forecast, fahrenheit: Boolean) {
    val c = f.current
    Spacer(Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.Top) {
        Row(Modifier.weight(1f)) {
            DotText("${Units.temp(c.tempC, fahrenheit)}", 128.sp, letterSpacing = 0.sp)
            DotText("°", 64.sp, color = Ink.Red, modifier = Modifier.padding(top = 10.dp))
        }
        PixelGlyph(glyphFor(c.code, c.isDay), 104.dp, modifier = Modifier.padding(top = 18.dp), unlit = Ink.Faint)
    }
    DotText(WeatherCodes.label(c.code), 30.sp, maxLines = 2)
    Spacer(Modifier.height(8.dp))
    val parts = buildList {
        c.feelsC?.let { add("Feels ${Units.temp(it, fahrenheit)}°") }
        f.today?.let { add("H ${Units.temp(it.hiC, fahrenheit)}°"); add("L ${Units.temp(it.loC, fahrenheit)}°") }
    }
    DotText(parts.joinToString("   "), 17.sp, color = Ink.Dim)
}

@Composable
private fun Section(title: String, tag: String?) {
    Spacer(Modifier.height(30.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        DotText(title, 17.sp, modifier = Modifier.weight(1f))
        if (tag != null) MonoText(tag.uppercase(), size = 10.sp)
    }
    Spacer(Modifier.height(6.dp))
    DotRule()
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun HourlyStrip(f: Forecast, fahrenheit: Boolean) {
    if (f.hours.isEmpty()) return
    val lo = f.hours.minOf { it.tempC }
    val span = (f.hours.maxOf { it.tempC } - lo).coerceAtLeast(1.0)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(end = 8.dp)) {
        items(f.hours, key = { it.time.toString() }) { h ->
            val now = h == f.hours.first()
            val label = when {
                now -> "Now"
                h.time.hour == 0 -> dayFmt.format(h.time)
                else -> hourFmt.format(h.time).lowercase()
            }
            Column(Modifier.width(46.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                DotText(label, 12.sp, color = if (now) Ink.Red else Ink.Dim, upper = now || h.time.hour == 0)
                Spacer(Modifier.height(6.dp))
                PixelGlyph(glyphFor(h.code, h.isDay), 26.dp)
                Spacer(Modifier.height(6.dp))
                DotText("${Units.temp(h.tempC, fahrenheit)}°", 17.sp)
                Spacer(Modifier.height(6.dp))
                // A column of dots: the temperature as an LED bar.
                val level = (((h.tempC - lo) / span) * 9).toInt() + 1
                Canvas(Modifier.width(10.dp).height(64.dp)) {
                    val step = size.height / 10f
                    for (i in 0 until 10) {
                        val lit = i < level
                        drawCircle(
                            if (lit) (if (now) Ink.Red else Ink.White) else Ink.Faint,
                            radius = step * if (lit) 0.36f else 0.2f,
                            center = Offset(size.width / 2, size.height - (i + 0.5f) * step),
                        )
                    }
                }
                val p = h.precipMm ?: 0.0
                Spacer(Modifier.height(4.dp))
                MonoText(if (p >= 0.1) Units.precip(p, fahrenheit).substringBefore(' ') else " ", size = 9.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Daily(f: Forecast, fahrenheit: Boolean) {
    if (f.days.isEmpty()) return
    val lo = f.days.minOf { it.loC }
    val span = (f.days.maxOf { it.hiC } - lo).coerceAtLeast(1.0)
    val today = f.days.first().date
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        f.days.forEach { d ->
            val isToday = d.date == today
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(62.dp)) {
                    DotText(dayLabel(d.date, today), 17.sp, color = if (isToday) Ink.Red else Ink.White)
                    MonoText(d.source.label, size = 8.sp, maxLines = 1)
                }
                PixelGlyph(glyphFor(d.code, true), 24.dp)
                Spacer(Modifier.width(10.dp))
                DotText("${Units.temp(d.loC, fahrenheit)}°", 16.sp, color = Ink.Dim, modifier = Modifier.width(40.dp))
                // Dot range bar across the week's range.
                Canvas(Modifier.weight(1f).height(12.dp)) {
                    val n = 18
                    val step = size.width / n
                    val a = ((d.loC - lo) / span * (n - 1)).toInt()
                    val b = ((d.hiC - lo) / span * (n - 1)).toInt().coerceAtLeast(a)
                    for (i in 0 until n) {
                        val lit = i in a..b
                        drawCircle(
                            if (lit) Ink.White else Ink.Faint,
                            radius = step * if (lit) 0.34f else 0.18f,
                            center = Offset((i + 0.5f) * step, size.height / 2),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                DotText("${Units.temp(d.hiC, fahrenheit)}°", 16.sp, modifier = Modifier.width(40.dp))
            }
        }
    }
}

private fun dayLabel(d: LocalDate, today: LocalDate) = when (d) {
    today -> "Today"
    else -> dayFmt.format(d)
}

@Composable
private fun Details(f: Forecast, fahrenheit: Boolean) {
    val c = f.current
    val tiles = buildList {
        c.windKmh?.let { add("Wind" to Units.wind(it, fahrenheit)) }
        c.humidity?.let { add("Humidity" to "${Math.round(it)}%") }
        f.today?.sunrise?.let { add("Sunrise" to clockFmt.format(it)) }
        f.today?.sunset?.let { add("Sunset" to clockFmt.format(it)) }
        f.today?.precipMm?.let { add("Precip today" to Units.precip(it, fahrenheit)) }
        c.feelsC?.let { add("Feels like" to "${Units.temp(it, fahrenheit)}°") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f).dotFrame(corner = 18.dp).padding(16.dp)) {
                        MonoText(label.uppercase(), size = 10.sp)
                        Spacer(Modifier.height(6.dp))
                        DotText(value, 22.sp)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AlertBlock(a: Alert) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .dotFrame(color = Ink.Red, corner = 18.dp)
            .clickable { open = !open }
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelGlyph(Glyphs.Alert, 26.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                DotText(a.event, 19.sp, color = if (a.isSevere) Ink.Red else Ink.White, maxLines = 2)
                formatEnds(a.ends)?.let { MonoText("Until $it", size = 12.sp) }
            }
            DotText(if (open) "−" else "+", 22.sp, color = Ink.Red)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            if (a.headline.isNotBlank()) MonoText(a.headline, color = Ink.White, size = 13.sp)
            if (a.description.isNotBlank()) {
                Spacer(Modifier.height(8.dp)); MonoText(a.description.trim(), color = Ink.White, size = 12.sp)
            }
            if (a.instruction.isNotBlank()) {
                Spacer(Modifier.height(8.dp)); MonoText(a.instruction.trim(), color = Ink.White, size = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
            MonoText("SOURCE: NATIONAL WEATHER SERVICE", size = 10.sp)
        }
    }
}
