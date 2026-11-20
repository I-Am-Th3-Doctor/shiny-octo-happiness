package com.something.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

/**
 * A frame drawn as a line of round dots around a rounded rectangle: the dot-matrix "border" used
 * for every widget and control. Zero-length dashes with round caps render as dots.
 */
fun Modifier.dotFrame(
    color: Color = Ink.Dim,
    corner: Dp = 20.dp,
    dot: Dp = 2.dp,
    gap: Dp = 6.dp,
    fill: Color = Ink.Black,
): Modifier = this.drawWithCache {
    val d = dot.toPx()
    val inset = d
    val r = corner.toPx()
    val path = Path().apply {
        addRoundRect(RoundRect(inset, inset, size.width - inset, size.height - inset, CornerRadius(r)))
    }
    val stroke = Stroke(width = d, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0f, gap.toPx())))
    onDrawBehind {
        if (fill.alpha > 0f) drawPath(path, fill)
        drawPath(path, color, style = stroke)
    }
}

/** The home backdrop: a quiet field of faint dots on black. */
fun Modifier.dotGrid(spacing: Dp = 18.dp, color: Color = Ink.Faint): Modifier = this
    .background(Ink.Black)
    .drawWithCache {
        val s = spacing.toPx()
        val r = 1.dp.toPx()
        onDrawBehind {
            var y = s / 2
            while (y < size.height) {
                var x = s / 2
                while (x < size.width) {
                    drawCircle(color, r, Offset(x, y))
                    x += s
                }
                y += s
            }
        }
    }

/** A dotted horizontal rule. */
@Composable
fun DotRule(modifier: Modifier = Modifier, color: Color = Ink.Dim) {
    Box(
        modifier.fillMaxWidth().height(4.dp).drawBehind {
            val step = 6.dp.toPx()
            var x = step / 2
            while (x < size.width) {
                drawCircle(color, 1.dp.toPx(), Offset(x, size.height / 2))
                x += step
            }
        }
    )
}

/** Dot-matrix text. Uppercase by default, like an LED readout. */
@Composable
fun DotText(
    text: String,
    size: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Ink.White,
    weight: Int = 900,
    upper: Boolean = true,
    maxLines: Int = 1,
    letterSpacing: TextUnit = 0.04.em(size),
) {
    Text(
        if (upper) text.uppercase() else text,
        modifier = modifier,
        style = TextStyle(
            fontFamily = Dot,
            fontWeight = FontWeight(weight),
            fontSize = size,
            color = color,
            letterSpacing = letterSpacing,
            lineHeight = size * 1.1f,
        ),
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

private fun Double.em(size: TextUnit): TextUnit = (size.value * this).sp

/** Small monospace caption, for the few places dots would be illegible. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ink.Dim,
    size: TextUnit = 11.sp,
    maxLines: Int = Int.MAX_VALUE,
    upper: Boolean = false,
) {
    Text(
        if (upper) text.uppercase() else text,
        modifier = modifier,
        style = TextStyle(fontFamily = Mono, fontSize = size, color = color, lineHeight = size * 1.4f),
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}

/** A pill button with a dotted outline. [accent] fills it red for the one primary action. */
@Composable
fun DotButton(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .height(48.dp)
            .dotFrame(color = if (accent) Ink.Red else Ink.White, corner = 24.dp, fill = if (accent) Ink.Red else Ink.Black)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        DotText(text, 17.sp, color = Ink.White)
    }
}

/** Single-line text field inside a dot frame. */
@Composable
fun DotField(
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
        textStyle = TextStyle(fontFamily = Dot, fontWeight = FontWeight(900), fontSize = 20.sp, color = Ink.White),
        cursorBrush = SolidColor(Ink.Red),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = imeAction),
        keyboardActions = KeyboardActions(onAny = { onImeAction() }),
        modifier = modifier
            .fillMaxWidth()
            .dotFrame(color = Ink.Dim, corner = 24.dp)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) DotText(placeholder, 20.sp, color = Ink.Dim, upper = false)
                inner()
            }
        },
    )
}

/** A popup menu in the house style: black, dot frame, dot-matrix items. */
@Composable
fun DotMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    offset: IntOffset = IntOffset(0, 0),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!expanded) return
    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(230.dp)
                .dotFrame(color = Ink.White, corner = 18.dp)
                .padding(vertical = 8.dp),
            content = content,
        )
    }
}

@Composable
fun DotMenuItem(text: String, red: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    DotText(
        text, 17.sp,
        color = when {
            !enabled -> Ink.Dim
            red -> Ink.Red
            else -> Ink.White
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
    )
}
