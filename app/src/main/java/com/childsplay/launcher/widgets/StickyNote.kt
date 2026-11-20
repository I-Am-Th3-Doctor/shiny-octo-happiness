package com.childsplay.launcher.widgets

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.childsplay.launcher.ui.Handwriting
import com.childsplay.launcher.ui.Toy
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

/** Sticky-note colours. Yellow first: it's the default. */
val NoteColors = listOf(
    Toy.NoteYellow,
    Color(0xFFFF8AD8), // pink
    Color(0xFF7DF9FF), // blue
    Color(0xFFB2FF59), // green
    Color(0xFFFFB74D), // orange
)

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
private val headFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

/**
 * The 2x2 sticky note: today's calendar, handwritten, stuck to the screen at a slight angle with
 * a curl of shadow. Tapping it is handled by the caller (opens the calendar, or asks permission).
 */
@Composable
fun StickyNote(
    colorIndex: Int,
    permissionGranted: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var events by remember { mutableStateOf<List<CalEvent>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    val noteColor = NoteColors[colorIndex.mod(NoteColors.size)]

    // Reload on permission change, provider changes, and every few minutes (catches midnight).
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
    LaunchedEffect(Unit) {
        while (true) { delay(5 * 60 * 1000L); reload++ }
    }
    val today = LocalDate.now()

    Box(modifier.padding(10.dp)) {
        Column(
            Modifier
                .fillMaxSize()
                .rotate(-3.5f)
                .shadow(10.dp, RectangleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .background(noteColor)
                .drawBehind {
                    // Adhesive strip: a slightly deeper band across the top.
                    drawRect(lerp(noteColor, Color(0xFFFF9800), 0.18f), size = Size(size.width, size.height * 0.13f))
                    // Curled corner, bottom right.
                    val c = size.width * 0.13f
                    val curl = Path().apply {
                        moveTo(size.width, size.height - c)
                        lineTo(size.width - c, size.height)
                        lineTo(size.width, size.height)
                        close()
                    }
                    drawPath(curl, Toy.Paper)
                    val fold = Path().apply {
                        moveTo(size.width, size.height - c)
                        lineTo(size.width - c, size.height)
                        lineTo(size.width - c * 0.95f, size.height - c * 0.95f)
                        close()
                    }
                    drawPath(fold, lerp(noteColor, Color(0xFF8D6E00), 0.35f))
                }
                .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Text(
                headFmt.format(today),
                fontFamily = Handwriting, fontWeight = FontWeight.Bold, fontSize = 23.sp, color = Toy.Ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            when {
                !permissionGranted -> Text(
                    "Tap so I can read\nyour calendar ✎",
                    fontFamily = Handwriting, fontSize = 21.sp, color = Toy.Ink, lineHeight = 22.sp,
                )
                events.isEmpty() -> Text(
                    "Nothing today!\nGo play :)",
                    fontFamily = Handwriting, fontSize = 22.sp, color = Toy.Ink, lineHeight = 23.sp,
                )
                else -> {
                    val shown = events.take(4)
                    shown.forEach { e -> EventLine(e) }
                    if (events.size > shown.size) {
                        Text(
                            "+ ${events.size - shown.size} more…",
                            fontFamily = Handwriting, fontSize = 18.sp, color = Toy.Ink.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }
        // A bit of tape.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-6).dp)
                .rotate(4f)
                .size(width = 54.dp, height = 18.dp)
                .background(Color(0xFFFF2BB4).copy(alpha = 0.85f))
        )
    }
}

@Composable
private fun EventLine(e: CalEvent) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(Color(e.color or 0xFF000000.toInt()), CircleShape))
        Spacer(Modifier.width(5.dp))
        val time = if (e.allDay) "all day" else timeFmt.format(Instant.ofEpochMilli(e.begin).atZone(ZoneId.systemDefault()))
        Text(
            "$time  ${e.title}",
            fontFamily = Handwriting, fontSize = 19.sp, color = Toy.Ink, lineHeight = 20.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}
