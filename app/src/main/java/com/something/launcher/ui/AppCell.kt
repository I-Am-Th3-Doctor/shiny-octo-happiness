package com.something.launcher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.something.launcher.data.AppEntry

/** An app's dot-matrix glyph, or a single dot-matrix initial while the glyph is computed. */
@Composable
fun DotAppIcon(app: AppEntry, icon: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (icon != null) {
            Image(icon, contentDescription = app.label, modifier = Modifier.size(size))
        } else {
            DotText(app.label.take(1), (size.value * 0.6f).sp)
        }
        if (app.isWorkProfile) {
            // Work apps get a red dot, top-right.
            Box(Modifier.align(Alignment.TopEnd).size(7.dp).background(Ink.Red, CircleShape))
        }
    }
}

/**
 * One app on a grid: glyph and dot-matrix label. Long-press opens [menu].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCell(
    app: AppEntry,
    icon: ImageBitmap?,
    onLaunch: (AppEntry, Rect) -> Unit,
    menu: @Composable (close: () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val haptics = LocalHapticFeedback.current
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .combinedClickable(
                    onClick = { onLaunch(app, bounds) },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); open = true },
                    onLongClickLabel = "Options for ${app.label}",
                )
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DotAppIcon(app, icon, 46.dp)
            Spacer(Modifier.height(8.dp))
            DotText(app.label, 12.sp, color = Ink.White, weight = 900, upper = false, modifier = Modifier.padding(horizontal = 2.dp))
        }
        DotMenu(expanded = open, onDismiss = { open = false }) { menu { open = false } }
    }
}
