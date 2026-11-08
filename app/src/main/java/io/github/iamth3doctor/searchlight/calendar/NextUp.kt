package io.github.iamth3doctor.searchlight.calendar

/**
 * "Next up" for the Weather tile: the event happening now, or else the next one starting within
 * [WINDOW_MS], with its live wording ("• Team Sync starts in 14 min (Zoom)",
 * "• In progress: Design Review (Ends in 22 min)") and its video-call link, if it has one.
 * All-day events never count (they'd hold the tile all day). Pure; JVM-tested.
 */
object NextUp {

    const val WINDOW_MS = 60 * 60_000L

    enum class State { STARTING, IN_PROGRESS }

    /** A video call link and what to call it: "Zoom", "Meet", "Teams", or null ("video link"). */
    class Call(val url: String, val platform: String?)

    class Status(val event: DayEvent, val state: State, val minutes: Long, val call: Call?)

    /**
     * What the tile shows at [nowMs]: an event in progress (the one ending first) wins over one
     * starting soon (the one starting first); null outside both, when the tile is weather.
     */
    fun pick(events: List<DayEvent>, nowMs: Long, windowMs: Long = WINDOW_MS): Status? {
        val timed = events.filter { !it.allDay && it.end > it.begin }
        timed.filter { it.begin <= nowMs && nowMs < it.end }
            .minWithOrNull(compareBy<DayEvent>({ it.end }, { it.begin }, { it.title }))
            ?.let { return Status(it, State.IN_PROGRESS, ceilMinutes(it.end - nowMs), callOf(it)) }
        return timed.filter { it.begin > nowMs && it.begin - nowMs <= windowMs }
            .minWithOrNull(compareBy<DayEvent>({ it.begin }, { it.title }))
            ?.let { Status(it, State.STARTING, ceilMinutes(it.begin - nowMs), callOf(it)) }
    }

    /** Rounded up: never "in 0 min". */
    fun ceilMinutes(ms: Long) = ((ms + 59_999L) / 60_000L).coerceAtLeast(1)

    /** "14 min" (up to 60), "1 hr 10 min", "2 hr". */
    fun duration(minutes: Long): String = when {
        minutes <= 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }

    /** The tile's line, without its leading bullet. */
    fun text(s: Status, untitled: String): String {
        val title = s.event.title.ifBlank { untitled }
        return when (s.state) {
            State.STARTING -> "$title starts in ${duration(s.minutes)}" + (s.call?.let { " (${it.platform ?: "video link"})" } ?: "")
            State.IN_PROGRESS -> "In progress: $title (Ends in ${duration(s.minutes)})"
        }
    }

    // ------------------------------------------------------------------ video calls

    private val urlRx = Regex("""(?:https?://|\b)((?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s<>"'()\[\]]*)?)""", RegexOption.IGNORE_CASE)

    /** Known call hosts: the three named ones, then other video services ("video link"). */
    private val NAMED = listOf("zoom.us" to "Zoom", "zoomgov.com" to "Zoom", "meet.google.com" to "Meet",
        "teams.microsoft.com" to "Teams", "teams.live.com" to "Teams")
    private val OTHER_VIDEO = listOf("webex.com", "meet.jit.si", "whereby.com", "bluejeans.com", "gotomeeting.com",
        "gotomeet.me", "meet.goto.com", "chime.aws", "join.skype.com", "around.co", "vc.ringcentral.com", "meetings.ringcentral.com",
        "app.livestorm.co", "pexip.me", "8x8.vc", "meet.lync.com")

    private fun hostMatches(host: String, domain: String) = host == domain || host.endsWith(".$domain")

    /** The event's video-call link (location first, then description); null when it has none. */
    fun callOf(e: DayEvent): Call? = callIn(e.location) ?: callIn(e.description)

    fun callIn(text: String?): Call? {
        if (text.isNullOrBlank()) return null
        var other: Call? = null
        for (m in urlRx.findAll(text)) {
            val raw = m.value.trimEnd('.', ',', ';', ':', '!', '?')
            val url = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
            val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').lowercase()
            NAMED.firstOrNull { hostMatches(host, it.first) }?.let { return Call(url, it.second) }
            if (other == null && OTHER_VIDEO.any { hostMatches(host, it) }) other = Call(url, null)
        }
        return other
    }
}
