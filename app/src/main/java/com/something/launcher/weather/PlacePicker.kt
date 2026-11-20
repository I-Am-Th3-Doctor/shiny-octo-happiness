package com.something.launcher.weather

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.something.launcher.ui.DotButton
import com.something.launcher.ui.DotField
import com.something.launcher.ui.DotRule
import com.something.launcher.ui.DotText
import com.something.launcher.ui.Ink
import com.something.launcher.ui.MonoText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** City search backed by Open-Meteo's free geocoder, plus "use my location". */
@Composable
fun PlacePicker(onPick: (Place) -> Unit, onUseLocation: () -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onDismiss)

    LaunchedEffect(query) {
        if (query.trim().length < 2) { results = emptyList(); status = null; return@LaunchedEffect }
        delay(350)
        status = "Searching…"
        val found = withContext(Dispatchers.IO) { runCatching { WeatherApi.searchPlaces(query) } }
        results = found.getOrDefault(emptyList())
        status = when {
            found.isFailure -> "Couldn't search right now."
            results.isEmpty() -> "No places called that."
            else -> null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Ink.Black)
            .clickable(remember { MutableInteractionSource() }, null) {}
            .systemBarsPadding()
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DotText("Place", 30.sp, modifier = Modifier.weight(1f))
            DotText("Close", 16.sp, color = Ink.Dim, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
        }
        DotField(
            value = query,
            onValueChange = { query = it },
            placeholder = "City or town",
            imeAction = ImeAction.Search,
            onImeAction = { results.firstOrNull()?.let(onPick) },
        )
        status?.let { MonoText(it, size = 13.sp) }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(results) { p ->
                Column(Modifier.fillMaxWidth().clickable { onPick(p) }) {
                    DotText(p.name, 19.sp, upper = false, modifier = Modifier.padding(vertical = 14.dp))
                    DotRule()
                }
            }
        }
        DotButton("Use my location", Modifier.fillMaxWidth(), accent = true, onClick = onUseLocation)
    }
}
