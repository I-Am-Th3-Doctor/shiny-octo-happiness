package com.childsplay.launcher.things

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.data.AppEntry
import com.childsplay.launcher.data.HomeStore
import com.childsplay.launcher.data.Thing
import com.childsplay.launcher.data.ThingShape
import com.childsplay.launcher.ui.AppIcon
import com.childsplay.launcher.ui.Bubbly
import com.childsplay.launcher.ui.Chunky
import com.childsplay.launcher.ui.Toy
import com.childsplay.launcher.ui.toyBlock

/**
 * Make or change a thing: pick its shape, its colour, its name, and which apps live inside.
 * [existing] is null when making a new thing.
 */
@Composable
fun ThingEditor(
    existing: Thing?,
    allApps: List<AppEntry>,
    icons: Map<String, ImageBitmap>,
    onSave: (Thing) -> Unit,
    onDelete: (Thing) -> Unit,
    onDismiss: () -> Unit,
) {
    var shape by rememberSaveable { mutableStateOf(existing?.shape ?: ThingShape.CLOUD) }
    var color by rememberSaveable { mutableStateOf(existing?.color ?: 0) }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var query by rememberSaveable { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(existing?.appKeys.orEmpty()) } }
    var confirmDelete by remember { mutableStateOf(false) }
    val tc = Toy.thingColors[color.mod(Toy.thingColors.size)]

    BackHandler(onBack = onDismiss)

    val filtered = remember(allApps, query) {
        val q = query.trim()
        if (q.isEmpty()) allApps else allApps.filter { it.label.contains(q, ignoreCase = true) }
    }
    // Chosen apps float to the top so you can see what's inside at a glance.
    val ordered = remember(filtered, selected.toList()) {
        val sel = selected.toSet()
        filtered.sortedByDescending { it.key in sel }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(tint(tc, 0.55f))
            .clickable(enabled = false) {}
            .systemBarsPadding()
            .imePadding()
    ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (existing == null) "New thing!" else "Fix up thing",
                        fontFamily = Chunky,
                        fontSize = 34.sp,
                        color = Toy.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    ShapeArt(shape, tc, Modifier.size(84.dp))
                }
            }
            item { SectionLabel("What is it?") }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ThingShape.entries.forEach { sh ->
                        val on = sh == shape
                        Column(
                            Modifier
                                .toyBlock(if (on) Toy.Yellow else Toy.Paper, corner = 20.dp, shadow = if (on) 5.dp else 3.dp)
                                .clickable { shape = sh }
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ShapeArt(sh, tc, Modifier.size(64.dp))
                            Text(sh.title, fontFamily = Chunky, fontSize = 15.sp, color = Toy.Ink)
                        }
                    }
                }
            }
            item { SectionLabel("Paint it") }
            item {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Toy.thingColors.forEachIndexed { i, c ->
                        Box(
                            Modifier
                                .size(if (i == color) 50.dp else 42.dp)
                                .background(c, CircleShape)
                                .border(if (i == color) 5.dp else 3.dp, Toy.Ink, CircleShape)
                                .clickable { color = i }
                        )
                    }
                }
            }
            item { SectionLabel("Name it") }
            item {
                ToyTextField(
                    value = name,
                    onValueChange = { name = it.take(28) },
                    placeholder = shape.title,
                )
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel("What's inside? (${selected.size})", Modifier.weight(1f))
                }
            }
            item {
                ToyTextField(value = query, onValueChange = { query = it }, placeholder = "Find an app…")
            }
            items(ordered, key = { it.key }) { app ->
                val on = app.key in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toyBlock(if (on) tc else Color.White, corner = 18.dp, shadow = 3.dp, border = 2.5.dp)
                        .clickable { if (on) selected.remove(app.key) else selected.add(app.key) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app, icons[app.key], 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        app.label + if (app.isWorkProfile) " (work)" else "",
                        fontFamily = Bubbly,
                        fontSize = 17.sp,
                        color = if (on) Toy.onColor(tc) else Toy.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier
                            .size(28.dp)
                            .background(if (on) Toy.Yellow else Toy.Paper, CircleShape)
                            .border(2.5.dp, Toy.Ink, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Text("✓", fontFamily = Chunky, fontSize = 16.sp, color = Toy.Ink)
                    }
                }
            }
        }

        // Sticky action bar.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(tint(tc, 0.55f))
                .border(3.dp, Toy.Ink)
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ToyButton("Cancel", Toy.Paper, Modifier.weight(1f), onClick = onDismiss)
            if (existing != null) {
                ToyButton("Toss", Toy.Red, Modifier.weight(1f)) { confirmDelete = true }
            }
            ToyButton("Save!", Toy.Lime, Modifier.weight(1.3f)) {
                val keep = allApps.map { it.key }.toSet()
                // Keep the order the user picked; drop apps that were uninstalled meanwhile.
                val keys = selected.filter { it in keep || allApps.isEmpty() }
                onSave(
                    Thing(
                        id = existing?.id ?: HomeStore.newId(),
                        name = name.trim().ifEmpty { shape.title },
                        shape = shape,
                        color = color,
                        appKeys = keys,
                    )
                )
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Toss ${existing.name}?", fontFamily = Chunky) },
            text = { Text("The apps inside stay installed. Only the ${existing.shape.title.lowercase()} goes away.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(existing) }) { Text("Toss it", color = Toy.Red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, fontFamily = Chunky, fontSize = 21.sp, color = Toy.Ink, modifier = modifier.padding(top = 6.dp))
}

@Composable
fun ToyButton(text: String, color: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(54.dp)
            .toyBlock(color, corner = 50.dp, shadow = 5.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontFamily = Chunky, fontSize = 20.sp, color = Toy.onColor(color))
    }
}

@Composable
fun ToyTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Done,
    onImeAction: () -> Unit = {},
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontFamily = Bubbly, fontSize = 19.sp, color = Toy.Ink),
        cursorBrush = SolidColor(Toy.Pink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = imeAction),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier
            .fillMaxWidth()
            .toyBlock(Color.White, corner = 18.dp, shadow = 4.dp)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, fontFamily = Bubbly, fontSize = 19.sp, color = Toy.Ink.copy(alpha = 0.4f))
                inner()
            }
        },
    )
}
