package com.something.launcher.widgets

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.something.launcher.ui.DotText
import com.something.launcher.ui.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

data class CalEvent(val title: String, val begin: Long, val end: Long, val allDay: Boolean, val color: Int)

object CalendarRepo {
    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /**
     * Today's events from the device calendar provider, which is where Google Calendar syncs to.
     * All-day events are stored in UTC, so they're matched by date rather than by instant.
     */
    fun today(context: Context): List<CalEvent> {
        if (!hasPermission(context)) return emptyList()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            // Widen by a day each side so UTC all-day events are always included, then filter.
            ContentUris.appendId(it, start - DAY)
            ContentUris.appendId(it, end + DAY)
        }.build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
        )
        val out = ArrayList<CalEvent>()
        runCatching {
            context.contentResolver.query(
                uri, projection,
                "${CalendarContract.Instances.VISIBLE} = 1 AND ${CalendarContract.Instances.STATUS} != ${CalendarContract.Instances.STATUS_CANCELED}",
                null, "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val title = c.getString(0)?.takeIf { it.isNotBlank() } ?: "(No title)"
                    val b = c.getLong(1)
                    val e = c.getLong(2)
                    val allDay = c.getInt(3) == 1
                    val keep = if (allDay) {
                        val first = Instant.ofEpochMilli(b).atZone(ZoneOffset.UTC).toLocalDate()
                        val last = Instant.ofEpochMilli(e - 1).atZone(ZoneOffset.UTC).toLocalDate()
                        !today.isBefore(first) && !today.isAfter(last)
                    } else {
                        b < end && e > start
                    }
                    if (keep) out += CalEvent(title, b, e, allDay, c.getInt(4))
                }
            }
        }
        return out.distinctBy { Triple(it.title, it.begin, it.allDay) }
            .sortedWith(compareByDescending<CalEvent> { it.allDay }.thenBy { it.begin })
    }

    /** Opens the calendar app on today. Google Calendar handles this URI. */
    fun openToday(context: Context) {
        val uri = CalendarContract.CONTENT_URI.buildUpon().appendPath("time")
            .also { ContentUris.appendId(it, System.currentTimeMillis()) }.build()
        val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure {
            runCatching {
                context.startActivity(
                    Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    private const val DAY = 24 * 60 * 60 * 1000L
}

private val timeFmt = DateTimeFormatter.ofPattern("h:mm", Locale.getDefault())
private val headFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** The note's outline: a rectangle with stair-stepped corners and a pixel fold bottom-right. */
private fun notePath(w: Float, h: Float, p: Float) = Path().apply {
    moveTo(2 * p, 0f)
    lineTo(w - 2 * p, 0f); lineTo(w - 2 * p, p); lineTo(w - p, p); lineTo(w - p, 2 * p); lineTo(w, 2 * p)
    lineTo(w, h - 4 * p)
    // Fold: a staircase diagonal cutting the corner.
    for (i in 1..4) { lineTo(w - (i - 1) * p - p, h - 4 * p + (i - 1) * p); lineTo(w - i * p, h - 4 * p + i * p) }
    lineTo(2 * p, h); lineTo(2 * p, h - p); lineTo(p, h - p); lineTo(p, h - 2 * p); lineTo(0f, h - 2 * p)
    lineTo(0f, 2 * p); lineTo(p, 2 * p); lineTo(p, p); lineTo(2 * p, p)
    close()
}

/**
 * The 2x2 sticky note, re-made in dot-matrix: white dot-grid paper with pixel-stepped edges and
 * a pixel fold, a red pixel pushpin, dot-matrix text, a slight tilt and a halftone dot shadow.
 * Shows today's events from the calendar provider (where Google Calendar syncs).
 */
@Composable
fun StickyNote(permissionGranted: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<CalEvent>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }

    DisposableEffect(permissionGranted) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reload++ }
        }
        if (permissionGranted) {
            runCatching { context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer) }
        }
        onDispose { runCatching { context.contentResolver.unregisterContentObserver(observer) } }
    }
    LaunchedEffect(permissionGranted, reload) {
        events = if (permissionGranted) withContext(Dispatchers.IO) { CalendarRepo.today(context) } else emptyList()
    }
    LaunchedEffect(Unit) { while (true) { delay(5 * 60 * 1000L); reload++ } }
    val today = LocalDate.now()

    Box(modifier.padding(8.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .rotate(-3f)
                .drawBehind {
                    val p = 4.dp.toPx()
                    val shape = notePath(size.width, size.height, p)
                    // Halftone shadow, offset down-right.
                    val sx = 7.dp.toPx(); val sy = 9.dp.toPx()
                    translate(sx, sy) {
                        clipPath(shape) {
                            val step = 4.dp.toPx()
                            var y = 0f; var row = 0
                            while (y < size.height) {
                                var x = if (row % 2 == 0) 0f else step / 2
                                while (x < size.width) { drawCircle(Ink.White.copy(alpha = 0.32f), step * 0.22f, Offset(x, y)); x += step }
                                y += step; row++
                            }
                        }
                    }
                    // Paper.
                    drawPath(shape, Ink.White)
                    // Dot-grid paper texture.
                    clipPath(shape) {
                        val g = 12.dp.toPx()
                        var y = g
                        while (y < size.height) {
                            var x = g
                            while (x < size.width) { drawCircle(Ink.Black.copy(alpha = 0.12f), 0.9.dp.toPx(), Offset(x, y)); x += g }
                            y += g
                        }
                    }
                    // Pixel fold flap: the cells above the staircase, in a light shade.
                    val fx = size.width - 4 * p; val fy = size.height - 4 * p
                    for (cy in 0 until 4) for (cx in 0 until 4) if (cx + cy <= 3) {
                        drawRect(Ink.Black.copy(alpha = 0.16f), Offset(fx + cx * p, fy + cy * p), Size(p, p))
                    }
                    // Red pixel pushpin, top centre.
                    val pin = listOf(0 to 0, 1 to 0, -1 to 0, 0 to -1, 0 to 1, 1 to 1, -1 to 1, 1 to -1, -1 to -1)
                    pin.forEach { (dx, dy) ->
                        drawCircle(Ink.Red, p * 0.5f, Offset(size.width / 2 + dx * p, p * 2.2f + dy * p))
                    }
                }
                .padding(start = 14.dp, end = 12.dp, top = 22.dp, bottom = 12.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DotText("Today", 13.sp, color = Ink.Red)
                    Spacer(Modifier.width(6.dp))
                    DotText(headFmt.format(today), 13.sp, color = Ink.Black)
                }
                Spacer(Modifier.height(8.dp))
                when {
                    !permissionGranted -> DotText("Tap to show your calendar", 14.sp, color = Ink.Black, upper = false, maxLines = 3)
                    events.isEmpty() -> DotText("Nothing today.", 15.sp, color = Ink.Black, upper = false, maxLines = 2)
                    else -> {
                        val shown = events.take(4)
                        shown.forEach { e -> EventLine(e) }
                        if (events.size > shown.size) {
                            DotText("+${events.size - shown.size} more", 12.sp, color = Ink.Red, upper = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventLine(e: CalEvent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
        val time = if (e.allDay) "ALL" else timeFmt.format(Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()))
        DotText(time, 12.sp, color = Ink.Red, modifier = Modifier.width(40.dp).padding(top = 1.dp))
        DotText(e.title, 13.sp, color = Ink.Black, upper = false, maxLines = 1)
    }
}
