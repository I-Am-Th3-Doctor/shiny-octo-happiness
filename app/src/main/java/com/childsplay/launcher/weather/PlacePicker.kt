package com.childsplay.launcher.weather

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
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
import com.childsplay.launcher.things.ToyButton
import com.childsplay.launcher.things.ToyTextField
import com.childsplay.launcher.ui.Bubbly
import com.childsplay.launcher.ui.Chunky
import com.childsplay.launcher.ui.Toy
import com.childsplay.launcher.ui.toyBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** City search backed by Open-Meteo's free geocoder, plus "use my location". */
@Composable
fun PlacePicker(
    skin: Skin,
    onPick: (Place) -> Unit,
    onUseLocation: () -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Place>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onDismiss)

    LaunchedEffect(query) {
        if (query.trim().length < 2) {
            results = emptyList(); status = null; return@LaunchedEffect
        }
        delay(350) // debounce typing
        status = "Searching…"
        val found = withContext(Dispatchers.IO) { runCatching { WeatherApi.searchPlaces(query) } }
        results = found.getOrDefault(emptyList())
        status = when {
            found.isFailure -> "Couldn't search right now."
            results.isEmpty() -> "No places called that."
            else -> null
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Toy.Ink.copy(alpha = 0.55f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
            .systemBarsPadding()
            .imePadding()
            .padding(18.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .toyBlock(skin.card, corner = 30.dp)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Where to?", fontFamily = Chunky, fontSize = 30.sp, color = skin.onCard)
            ToyTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "City or town",
                imeAction = ImeAction.Search,
                onImeAction = { results.firstOrNull()?.let(onPick) },
            )
            status?.let { Text(it, fontFamily = Bubbly, fontSize = 15.sp, color = skin.onCard) }
            LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results) { p ->
                    Text(
                        p.name,
                        fontFamily = Bubbly, fontSize = 18.sp, color = Toy.Ink,
                        modifier = Modifier
                            .fillMaxWidth()
                            .toyBlock(androidx.compose.ui.graphics.Color.White, corner = 16.dp, shadow = 3.dp, border = 2.5.dp)
                            .clickable { onPick(p) }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            ToyButton("Use my location", skin.accent, Modifier.fillMaxWidth(), onClick = onUseLocation)
        }
    }
}
