package com.childsplay.launcher.things

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.data.AppEntry
import com.childsplay.launcher.data.Thing
import com.childsplay.launcher.ui.AppIcon
import com.childsplay.launcher.ui.Chunky
import com.childsplay.launcher.ui.Toy
import com.childsplay.launcher.ui.toyBlock

/**
 * The pop-up panel that springs out of a thing. It scales up from the object's centre with a
 * bouncy spring and shrinks back into it on close.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThingPanel(
    thing: Thing,
    apps: List<AppEntry>,
    icons: Map<String, ImageBitmap>,
    origin: Rect,
    closeRequested: Boolean,
    onLaunch: (AppEntry, Rect) -> Unit,
    onEdit: () -> Unit,
    onRemoveApp: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onClosed: () -> Unit,
) {
    val color = thingColor(thing)
    val onColor = Toy.onColor(color)
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    var panelBounds by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, spring(dampingRatio = 0.58f, stiffness = 420f))
    }
    LaunchedEffect(closing, closeRequested) {
        if (closing || closeRequested) {
            progress.animateTo(0f, tween(170, easing = FastOutLinearInEasing))
            onClosed()
        }
    }
    BackHandler { closing = true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxH = maxHeight
        // Scrim: a flat, bold wash, never blurred or frosted.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value.coerceIn(0f, 1f) }
                .background(Toy.Ink.copy(alpha = 0.32f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { closing = true }
        )
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 18.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxH * 0.78f)
                    .onGloballyPositioned { panelBounds = it.boundsInRoot() }
                    .graphicsLayer {
                        val p = progress.value
                        val pb = panelBounds
                        if (pb.width > 0f && origin != Rect.Zero) {
                            transformOrigin = TransformOrigin(
                                (origin.center.x - pb.left) / pb.width,
                                (origin.center.y - pb.top) / pb.height,
                            )
                        }
                        scaleX = 0.12f + 0.88f * p
                        scaleY = 0.12f + 0.88f * p
                        alpha = (p * 3f).coerceIn(0f, 1f)
                        rotationZ = (1f - p) * -8f
                    }
                    .toyBlock(color, corner = 34.dp)
                    // Swallow taps so they don't reach the scrim.
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShapeArt(thing.shape, tint(color, 0.35f), Modifier.size(48.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        thing.name,
                        fontFamily = Chunky,
                        fontSize = 30.sp,
                        color = onColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Edit",
                        fontFamily = Chunky,
                        fontSize = 16.sp,
                        color = Toy.Ink,
                        modifier = Modifier
                            .toyBlock(Toy.Yellow, corner = 50.dp, shadow = 3.dp)
                            .clickable(onClick = onEdit)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                if (apps.isEmpty()) {
                    Text(
                        "Nothing in here yet!\nTap Edit to fill it with apps.",
                        fontFamily = Chunky,
                        fontSize = 20.sp,
                        color = onColor,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 28.dp),
                    )
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(4),
                        contentPadding = PaddingValues(bottom = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(apps, key = { it.key }) { app ->
                            PanelApp(
                                app = app,
                                icon = icons[app.key],
                                labelColor = onColor,
                                thingName = thing.name,
                                onLaunch = onLaunch,
                                onRemove = { onRemoveApp(app) },
                                onInfo = { onAppInfo(app) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PanelApp(
    app: AppEntry,
    icon: ImageBitmap?,
    labelColor: androidx.compose.ui.graphics.Color,
    thingName: String,
    onLaunch: (AppEntry, Rect) -> Unit,
    onRemove: () -> Unit,
    onInfo: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
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
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIcon(app, icon, 54.dp)
            Spacer(Modifier.height(5.dp))
            Text(
                app.label,
                color = labelColor,
                style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, offset = androidx.compose.ui.unit.DpOffset(0.dp, 0.dp)) {
            DropdownMenuItem(text = { Text("Take out of $thingName") }, onClick = { menu = false; onRemove() })
            DropdownMenuItem(text = { Text("App info") }, onClick = { menu = false; onInfo() })
        }
    }
}
