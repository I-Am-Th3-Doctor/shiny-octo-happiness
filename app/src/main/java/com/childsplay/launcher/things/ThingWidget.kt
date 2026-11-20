package com.childsplay.launcher.things

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.data.AppEntry
import com.childsplay.launcher.data.Thing
import com.childsplay.launcher.data.ThingShape
import com.childsplay.launcher.ui.AppIcon
import com.childsplay.launcher.ui.Chunky
import com.childsplay.launcher.ui.Toy
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

fun thingColor(t: Thing): Color = Toy.thingColors[t.color.mod(Toy.thingColors.size)]

/** Just the object's art, no icons; used in pickers and panel headers. */
@Composable
fun ShapeArt(shape: ThingShape, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.drawWithCache {
            val geo = shapeGeometry(shape, size)
            onDrawBehind {
                drawToyShadow(geo.silhouette, size)
                geo.draw(this, color)
            }
        }
    )
}

/**
 * A thing on the home board: the object, a peek at the apps inside, and its name.
 * It idles with a little bob, squishes when pressed and reports its bounds when tapped so the
 * panel can spring out of it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ThingWidget(
    thing: Thing,
    apps: List<AppEntry>,
    icons: Map<String, ImageBitmap>,
    onOpen: (Rect) -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
) {
    val color = thingColor(thing)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val squish by animateFloatAsState(
        if (pressed) 0.88f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "squish",
    )
    val phase = remember(thing.id) { (abs(thing.id.hashCode()) % 1000) / 1000f }
    val idle = rememberInfiniteTransition(label = "idle")
    val period = when (thing.shape) {
        ThingShape.CLOUD -> 5200
        ThingShape.BALLOON -> 4400
        ThingShape.ROCKET -> 1800
        else -> 6400
    }
    val tick = idle.animateFloat(0f, 1f, infiniteRepeatable(tween(period, easing = LinearEasing)), label = "tick")
    val coords = remember { arrayOfNulls<LayoutCoordinates>(1) }

    Column(
        modifier
            .onGloballyPositioned { coords[0] = it }
            .graphicsLayer {
                val v = if (animate) sin(((tick.value + phase) * 2 * PI).toFloat()) else 0f
                when (thing.shape) {
                    ThingShape.CLOUD -> { translationX = v * 5.dp.toPx(); translationY = v * 2.dp.toPx() }
                    ThingShape.BALLOON -> { translationY = v * 6.dp.toPx(); rotationZ = v * 2.5f }
                    ThingShape.ROCKET -> translationY = v * 2.dp.toPx()
                    ThingShape.TV -> rotationZ = v * 0.8f
                    ThingShape.PAINTING -> rotationZ = v * 1.5f
                    ThingShape.STATUE -> Unit
                }
                scaleX = squish
                scaleY = squish
            }
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = "Open ${thing.name}",
                onLongClickLabel = "Edit ${thing.name}",
                onLongClick = onLongPress,
                onClick = { coords[0]?.takeIf { it.isAttached }?.let { onOpen(it.boundsInRoot()) } ?: onOpen(Rect.Zero) },
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val density = LocalDensity.current
            val pxW = constraints.maxWidth.toFloat()
            val pxH = constraints.maxHeight.toFloat()
            val geo = remember(thing.shape, pxW, pxH) {
                shapeGeometry(thing.shape, androidx.compose.ui.geometry.Size(pxW, pxH))
            }
            Box(
                Modifier.matchParentSize().drawWithCache {
                    onDrawBehind {
                        drawToyShadow(geo.silhouette, size)
                        geo.draw(this, color)
                    }
                }
            )
            IconPeek(
                apps = apps,
                icons = icons,
                window = geo.window,
                onDark = thing.shape == ThingShape.TV || thing.shape == ThingShape.ROCKET,
                modifier = Modifier
                    .offset { IntOffset(geo.window.left.toInt(), geo.window.top.toInt()) }
                    .size(with(density) { geo.window.width.toDp() }, with(density) { geo.window.height.toDp() }),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            thing.name,
            fontFamily = Chunky,
            fontSize = 17.sp,
            color = Toy.onColor(color),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .background(color, RoundedCornerShape(50))
                .border(2.5.dp, Toy.Ink, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 3.dp),
        )
    }
}

/** Up to four icons in a 2x2 grid inside the object's window; "+N" when there are more. */
@Composable
private fun IconPeek(
    apps: List<AppEntry>,
    icons: Map<String, ImageBitmap>,
    window: Rect,
    onDark: Boolean,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val cell = with(density) { (minOf(window.width, window.height) / 2f).toDp() }
    val iconSize = cell * 0.82f
    Box(modifier, contentAlignment = Alignment.Center) {
        if (apps.isEmpty()) {
            Text(
                "+",
                fontFamily = Chunky,
                fontSize = (iconSize.value * 1.1f).sp,
                color = if (onDark) Color.White else Toy.Ink,
            )
            return@Box
        }
        val shown = if (apps.size > 4) apps.take(3) else apps
        val extra = apps.size - shown.size
        Column(verticalArrangement = Arrangement.spacedBy(cell * 0.1f)) {
            for (row in 0 until 2) {
                val start = row * 2
                if (start >= shown.size && !(extra > 0 && row == 1)) break
                Row(horizontalArrangement = Arrangement.spacedBy(cell * 0.1f)) {
                    for (col in 0 until 2) {
                        val i = start + col
                        when {
                            i < shown.size -> AppIcon(shown[i], icons[shown[i].key], iconSize)
                            i == 3 && extra > 0 -> Box(
                                Modifier.size(iconSize).background(Toy.Yellow, CircleShape).border(2.dp, Toy.Ink, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("+$extra", fontFamily = Chunky, fontSize = (iconSize.value * 0.36f).sp, color = Toy.Ink)
                            }
                        }
                    }
                }
            }
        }
    }
}
