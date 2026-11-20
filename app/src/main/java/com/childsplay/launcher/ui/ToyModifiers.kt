package com.childsplay.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The house style for every surface: a solid, saturated block with a thick ink outline and a hard
 * offset shadow, like a plastic toy or a sticker. No blur, no translucency.
 */
fun Modifier.toyBlock(
    color: Color,
    corner: Dp = 24.dp,
    shadow: Dp = 6.dp,
    border: Dp = 3.dp,
): Modifier {
    val shape = RoundedCornerShape(corner)
    return this
        .drawBehind {
            val off = shadow.toPx()
            val r = corner.toPx().coerceAtMost(minOf(size.width, size.height) / 2f)
            drawRoundRect(Toy.Ink, topLeft = Offset(off, off), size = size, cornerRadius = CornerRadius(r))
        }
        .clip(shape)
        .background(color)
        .border(border, Toy.Ink, shape)
}
