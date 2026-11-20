package com.childsplay.launcher.weather

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.things.ToyButton
import com.childsplay.launcher.ui.Bubbly
import com.childsplay.launcher.ui.Chunky
import com.childsplay.launcher.ui.Toy
import com.childsplay.launcher.ui.toyBlock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val hourFmt = DateTimeFormatter.ofPattern("h a", Locale.getDefault())
private val clockFmt = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
private val dayFmt = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
private val alertFmt = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())

/** Big chunky text with an ink outline and a hard shadow, readable on any sky. */
@Composable
fun OutlinedHeroText(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    outline: Float = 10f,
    textAlign: TextAlign? = null,
) {
    val base = TextStyle(fontFamily = Chunky, fontSize = size, textAlign = textAlign ?: TextAlign.Unspecified)
    Box(modifier) {
        Text(
            text,
            style = base.copy(
                color = Toy.Ink,
                drawStyle = Stroke(width = outline, join = androidx.compose.ui.graphics.StrokeJoin.Round),
                shadow = Shadow(Toy.Ink, Offset(outline * 0.6f, outline * 0.8f), 0f),
            ),
            maxLines = 1,
        )
        Text(text, style = base.copy(color = color), maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeatherScreen(store: WeatherStore) {
    val st by store.state.collectAsState()
    val skin by store.skin.collectAsState()
    val fahrenheit by store.fahrenheit.collectAsState()
    val manual by store.manualPlace.collectAsState()
    var picking by rememberSaveable { mutableStateOf(false) }
    val forecast = st.forecast
    val cond = forecast?.condition ?: Condition.PARTLY
    val isDay = forecast?.current?.isDay ?: true
    val (top, bottom) = skyColors(cond, isDay, skin)
    val onSky = Toy.onColor(lerp(top, bottom, 0.35f))

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        store.setManualPlace(null)
    }
    val useMyLocation = {
        picking = false
        if (store.hasLocationPermission()) store.setManualPlace(null)
        else permission.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    Box(Modifier.fillMaxSize().background(top)) {
        LivingScene(cond, isDay, skin, forecast?.current?.windKmh ?: 0.0, Modifier.fillMaxSize())

        PullToRefreshBox(
            isRefreshing = st.loading,
            onRefresh = { store.refresh(force = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val heroSpace = maxHeight * 0.62f
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .systemBarsPadding()
                        .padding(horizontal = 18.dp),
                ) {
                    TopBar(
                        place = forecast?.place?.name ?: manual?.name ?: "Hero Weather",
                        isManual = manual != null,
                        fahrenheit = fahrenheit,
                        onSky = onSky,
                        onUnits = { store.setFahrenheit(!fahrenheit) },
                        onPlace = { picking = true },
                    )
                    forecast?.alerts?.forEach { AlertCard(it) }

                    if (forecast == null) {
                        Spacer(Modifier.height(40.dp))
                        Column(
                            Modifier.fillMaxWidth().toyBlock(skin.card, corner = 30.dp).padding(22.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                if (st.loading) "Looking at the sky…" else "Where are you?",
                                fontFamily = Chunky, fontSize = 32.sp, color = skin.onCard, textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                st.error ?: "Share a rough location or pick a city to get the forecast.",
                                fontFamily = Bubbly, fontSize = 17.sp, color = skin.onCard, textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(16.dp))
                            ToyButton("Use my location", skin.accent, Modifier.fillMaxWidth(), onClick = useMyLocation)
                            Spacer(Modifier.height(12.dp))
                            ToyButton("Pick a city", Toy.Yellow, Modifier.fillMaxWidth()) { picking = true }
                        }
                        Spacer(Modifier.height(heroSpace))
                    } else {
                        Hero(forecast, fahrenheit, Modifier.height(heroSpace))
                        st.error?.let {
                            Text(it, fontFamily = Bubbly, color = Toy.Ink, modifier = Modifier.fillMaxWidth().toyBlock(Toy.Yellow, 16.dp, 3.dp).padding(12.dp))
                            Spacer(Modifier.height(12.dp))
                        }
                        HourlyCard(forecast, fahrenheit, skin)
                        Spacer(Modifier.height(16.dp))
                        DailyCard(forecast, fahrenheit, skin)
                        Spacer(Modifier.height(16.dp))
                        DetailsCard(forecast, fahrenheit)
                    }
                    Spacer(Modifier.height(16.dp))
                    SkinPicker(skin, onPick = store::setSkin)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        buildString {
                            append("Forecast: Open-Meteo — HRRR for the next 48 hours, ECMWF IFS for days 3–7.")
                            append(" Alerts: National Weather Service.")
                            forecast?.let {
                                append("\nUpdated ")
                                append(clockFmt.format(Instant.ofEpochMilli(it.fetchedAt).atZone(ZoneId.systemDefault())))
                            }
                        },
                        fontFamily = Bubbly, fontSize = 12.sp, color = onSky,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            picking,
            enter = fadeIn() + scaleIn(spring(dampingRatio = 0.6f), initialScale = 0.6f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
        ) {
            PlacePicker(
                skin = skin,
                onPick = { picking = false; store.setManualPlace(it) },
                onUseLocation = useMyLocation,
                onDismiss = { picking = false },
            )
        }
    }
}

@Composable
private fun TopBar(
    place: String,
    isManual: Boolean,
    fahrenheit: Boolean,
    onSky: Color,
    onUnits: () -> Unit,
    onPlace: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).clickable(onClick = onPlace).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (isManual) "★ " else "➤ ", fontFamily = Chunky, fontSize = 22.sp, color = onSky)
            Text(place, fontFamily = Chunky, fontSize = 24.sp, color = onSky, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (fahrenheit) "°F" else "°C",
            fontFamily = Chunky, fontSize = 18.sp, color = Toy.Ink,
            modifier = Modifier
                .toyBlock(Toy.Yellow, corner = 50.dp, shadow = 3.dp)
                .clickable(onClick = onUnits)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun Hero(f: Forecast, fahrenheit: Boolean, modifier: Modifier) {
    val c = f.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Spacer(Modifier.height(8.dp))
        OutlinedHeroText("${Units.temp(c.tempC, fahrenheit)}°", 150.sp, Color.White, outline = 16f)
        OutlinedHeroText(WeatherCodes.label(c.code), 38.sp, Toy.Yellow, outline = 9f)
        Spacer(Modifier.height(6.dp))
        val today = f.today
        val parts = buildList {
            c.feelsC?.let { add("Feels ${Units.temp(it, fahrenheit)}°") }
            today?.let { add("H ${Units.temp(it.hiC, fahrenheit)}°  L ${Units.temp(it.loC, fahrenheit)}°") }
        }
        if (parts.isNotEmpty()) {
            Text(
                parts.joinToString("   "),
                fontFamily = Chunky, fontSize = 22.sp, color = Toy.Ink,
                modifier = Modifier.toyBlock(Color.White, corner = 50.dp, shadow = 4.dp).padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: String, tag: String?, skin: Skin) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
        Text(title, fontFamily = Chunky, fontSize = 22.sp, color = skin.onCard, modifier = Modifier.weight(1f))
        if (tag != null) {
            Text(
                tag, fontFamily = Bubbly, fontSize = 11.sp, color = Toy.onColor(skin.accent),
                modifier = Modifier.background(skin.accent, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun HourlyCard(f: Forecast, fahrenheit: Boolean, skin: Skin) {
    val sources = f.hours.map { it.source }.toSet()
    val tag = when {
        sources == setOf(Source.HRRR) -> "HRRR"
        Source.HRRR in sources -> "HRRR + ECMWF"
        else -> "ECMWF · no HRRR here"
    }
    Column(Modifier.fillMaxWidth().toyBlock(skin.card, corner = 28.dp).padding(vertical = 16.dp)) {
        Box(Modifier.padding(horizontal = 16.dp)) { SectionTitle("Next 48 hours", tag, skin) }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(f.hours, key = { it.time.toString() }) { h ->
                val first = h == f.hours.first()
                val label = when {
                    first -> "Now"
                    h.time.hour == 0 -> dayFmt.format(h.time)
                    else -> hourFmt.format(h.time).lowercase()
                }
                Column(
                    Modifier
                        .width(62.dp)
                        .background(if (first) skin.accent else lerp(skin.card, skin.dayTop, 0.18f), RoundedCornerShape(20.dp))
                        .border(2.dp, Toy.Ink, RoundedCornerShape(20.dp))
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    val fg = if (first) Toy.onColor(skin.accent) else skin.onCard
                    Text(label, fontFamily = Bubbly, fontSize = 13.sp, color = fg, maxLines = 1)
                    Spacer(Modifier.height(4.dp))
                    WeatherGlyph(h.code, h.isDay, 30.dp)
                    Spacer(Modifier.height(4.dp))
                    Text("${Units.temp(h.tempC, fahrenheit)}°", fontFamily = Chunky, fontSize = 20.sp, color = fg)
                    val p = h.precipMm ?: 0.0
                    Text(
                        if (p >= 0.1) Units.precip(p, fahrenheit).substringBefore(' ') else " ",
                        fontFamily = Bubbly, fontSize = 11.sp, color = fg,
                    )
                }
            }
        }
    }
}

private fun tempColor(c: Double): Color = when {
    c < -5 -> Color(0xFF8A2BFF)
    c < 5 -> Color(0xFF2962FF)
    c < 12 -> Color(0xFF00A8FF)
    c < 18 -> Color(0xFF00D6C3)
    c < 24 -> Color(0xFF7BE000)
    c < 29 -> Color(0xFFFFD600)
    c < 34 -> Color(0xFFFF7A00)
    else -> Color(0xFFFF2E4D)
}

@Composable
private fun DailyCard(f: Forecast, fahrenheit: Boolean, skin: Skin) {
    if (f.days.isEmpty()) return
    val lo = f.days.minOf { it.loC }
    val hi = f.days.maxOf { it.hiC }
    val span = (hi - lo).coerceAtLeast(1.0)
    val todayDate = f.days.first().date
    Column(Modifier.fillMaxWidth().toyBlock(skin.card, corner = 28.dp).padding(16.dp)) {
        SectionTitle("7 days", "HRRR → ECMWF", skin)
        f.days.forEach { d ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(66.dp)) {
                    Text(dayName(d.date, todayDate), fontFamily = Chunky, fontSize = 19.sp, color = skin.onCard, maxLines = 1)
                    Text(d.source.label, fontFamily = Bubbly, fontSize = 10.sp, color = skin.onCard.copy(alpha = 0.6f))
                }
                WeatherGlyph(d.code, true, 32.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${Units.temp(d.loC, fahrenheit)}°", fontFamily = Chunky, fontSize = 18.sp,
                    color = skin.onCard.copy(alpha = 0.75f), modifier = Modifier.width(40.dp), textAlign = TextAlign.End,
                )
                Spacer(Modifier.width(8.dp))
                Canvas(Modifier.weight(1f).height(16.dp)) {
                    val w = size.width
                    drawRoundRect(lerp(skin.card, Toy.Ink, 0.12f), cornerRadius = CornerRadius(size.height / 2))
                    val x0 = ((d.loC - lo) / span * w).toFloat()
                    val x1 = ((d.hiC - lo) / span * w).toFloat().coerceAtLeast(x0 + size.height)
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(tempColor(d.loC), tempColor(d.hiC)), startX = x0, endX = x1),
                        topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height), cornerRadius = CornerRadius(size.height / 2),
                    )
                    drawRoundRect(
                        Toy.Ink, topLeft = Offset(x0, 0f), size = Size(x1 - x0, size.height),
                        cornerRadius = CornerRadius(size.height / 2), style = Stroke(2.dp.toPx()),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "${Units.temp(d.hiC, fahrenheit)}°", fontFamily = Chunky, fontSize = 18.sp, color = skin.onCard,
                    modifier = Modifier.width(40.dp),
                )
            }
        }
    }
}

private fun dayName(d: LocalDate, today: LocalDate) = when (d) {
    today -> "Today"
    today.plusDays(1) -> "Tmrw"
    else -> dayFmt.format(d)
}

@Composable
private fun DetailsCard(f: Forecast, fahrenheit: Boolean) {
    val c = f.current
    val tiles = buildList {
        c.windKmh?.let { add("Wind" to Units.wind(it, fahrenheit)) }
        c.humidity?.let { add("Humidity" to "${Math.round(it)}%") }
        f.today?.sunrise?.let { add("Sunrise" to clockFmt.format(it)) }
        f.today?.sunset?.let { add("Sunset" to clockFmt.format(it)) }
        f.today?.precipMm?.let { add("Rain today" to Units.precip(it, fahrenheit)) }
        c.feelsC?.let { add("Feels like" to "${Units.temp(it, fahrenheit)}°") }
    }
    if (tiles.isEmpty()) return
    val colors = listOf(Toy.Pink, Toy.Sky, Toy.Yellow, Toy.Orange, Toy.Lime, Toy.Purple)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { i, (label, value) ->
                    val bg = colors[(row * 2 + i) % colors.size]
                    Column(Modifier.weight(1f).toyBlock(bg, corner = 24.dp).padding(14.dp)) {
                        Text(label, fontFamily = Bubbly, fontSize = 14.sp, color = Toy.onColor(bg))
                        Text(value, fontFamily = Chunky, fontSize = 24.sp, color = Toy.onColor(bg), maxLines = 1)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun AlertCard(a: Alert) {
    var open by remember { mutableStateOf(false) }
    val bg = when (a.rank) {
        4 -> Color(0xFFD5003B)
        3 -> Toy.Red
        2 -> Toy.Orange
        else -> Toy.Yellow
    }
    val fg = Toy.onColor(bg)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .toyBlock(bg, corner = 22.dp)
            .clickable { open = !open }
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).background(Toy.Yellow, CircleShape).border(2.5.dp, Toy.Ink, CircleShape), contentAlignment = Alignment.Center) {
                Text("!", fontFamily = Chunky, fontSize = 20.sp, color = Toy.Ink)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.event, fontFamily = Chunky, fontSize = 21.sp, color = fg)
                formatEnds(a.ends)?.let { Text("Until $it", fontFamily = Bubbly, fontSize = 14.sp, color = fg) }
            }
            Text(if (open) "–" else "+", fontFamily = Chunky, fontSize = 26.sp, color = fg)
        }
        if (open) {
            Spacer(Modifier.height(8.dp))
            if (a.headline.isNotBlank()) Text(a.headline, fontFamily = Bubbly, fontSize = 15.sp, color = fg)
            if (a.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(a.description.trim(), fontFamily = Bubbly, fontSize = 14.sp, color = fg)
            }
            if (a.instruction.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(a.instruction.trim(), fontFamily = Bubbly, fontSize = 14.sp, color = fg)
            }
            Spacer(Modifier.height(4.dp))
            Text("Source: National Weather Service", fontFamily = Bubbly, fontSize = 11.sp, color = fg.copy(alpha = 0.8f))
        }
    }
}

fun formatEnds(iso: String?): String? = iso?.let {
    runCatching { alertFmt.format(OffsetDateTime.parse(it).atZoneSameInstant(ZoneId.systemDefault())) }.getOrNull()
}

@Composable
private fun SkinPicker(current: Skin, onPick: (Skin) -> Unit) {
    Column(Modifier.fillMaxWidth().toyBlock(current.card, corner = 28.dp).padding(16.dp)) {
        SectionTitle("Skins", null, current)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(Skins.all, key = { it.id }) { s ->
                val on = s.id == current.id
                Column(
                    Modifier.clickable { onPick(s) }.padding(4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(if (on) 64.dp else 56.dp)
                            .background(Brush.verticalGradient(listOf(s.dayTop, s.dayBottom, s.hillNear)), CircleShape)
                            .border(if (on) 5.dp else 3.dp, Toy.Ink, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(18.dp).background(s.sun, CircleShape).border(2.dp, Toy.Ink, CircleShape))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(s.name, fontFamily = Chunky, fontSize = 14.sp, color = current.onCard)
                }
            }
        }
    }
}
