package com.childsplay.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.data.AppEntry
import kotlin.math.abs

/** An app icon, or a bright lettered placeholder while icons are still loading. */
@Composable
fun AppIcon(app: AppEntry, icon: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    if (icon != null) {
        Image(
            bitmap = icon,
            contentDescription = app.label,
            filterQuality = FilterQuality.Medium,
            modifier = modifier.size(size),
        )
    } else {
        val color = Toy.thingColors[abs(app.key.hashCode()) % Toy.thingColors.size]
        Box(
            modifier.size(size).background(color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.label.take(1).uppercase(),
                color = Toy.onColor(color),
                fontFamily = Chunky,
                fontSize = (size.value * 0.45f).sp,
            )
        }
    }
}
