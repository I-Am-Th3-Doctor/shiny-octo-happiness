package io.github.iamth3doctor.searchlight.calendar

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Turns typed text into an event with Claude (the Anthropic API), as strict JSON (see [AiEventJson]
 * for reading it back). Called on a background thread; throws on any failure (no network, a bad
 * key, a refusal, unreadable output), and the caller falls back to [RuleParser].
 *
 * The key is passed in per call and only ever sent to the Anthropic API, never logged.
 */
class ClaudeEventParser(private val timeout: Duration = Duration.ofSeconds(20)) {

    /** The JSON describing the event, per [SCHEMA]. */
    fun parse(apiKey: String, text: String, today: LocalDate, defaultDate: LocalDate, zone: ZoneId): String {
        val client = client(apiKey)
        try {
            val params = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(4096L)
                .system(SYSTEM)
                .addUserMessage(prompt(text, today, defaultDate, zone))
                .outputConfig(
                    BetaOutputConfig.builder()
                        .effort(BetaOutputConfig.Effort.LOW) // a short extraction: quick, not deep
                        .format(BetaJsonOutputFormat.builder().schema(schema()).build())
                        .build(),
                )
                // If a safety classifier declines the request, the API retries it on a fallback model.
                .addBeta(FALLBACK_BETA)
                .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
                .build()
            val message = client.beta().messages().create(params)
            if (message.stopReason().orElse(null) == BetaStopReason.REFUSAL) throw IllegalStateException("refused")
            return message.content().firstNotNullOfOrNull { it.text().orElse(null)?.text() }
                ?: throw IllegalStateException("no text in the response")
        } finally {
            client.close()
        }
    }

    /** Checks a key without spending tokens: looks up the model the parser uses. */
    fun test(apiKey: String) {
        val client = client(apiKey)
        try {
            client.models().retrieve(MODEL)
        } finally {
            client.close()
        }
    }

    private fun client(apiKey: String): AnthropicClient =
        AnthropicOkHttpClient.builder().apiKey(apiKey).timeout(timeout).maxRetries(1).build()

    private fun prompt(text: String, today: LocalDate, defaultDate: LocalDate, zone: ZoneId): String {
        fun day(d: LocalDate) = "${d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} $d"
        return "Today is ${day(today)} (time zone ${zone.id}). If the text names no date, use ${day(defaultDate)}.\n" +
            "Text: $text"
    }

    private fun schema(): BetaJsonOutputFormat.Schema {
        val str = mapOf("type" to "string")
        val properties = mapOf(
            "understood" to mapOf("type" to "boolean", "description" to "false if the text doesn't describe an event"),
            "title" to mapOf("type" to "string", "description" to "the event's name, without the date, time or repeat words"),
            "date" to mapOf("type" to "string", "description" to "YYYY-MM-DD: the (first) day"),
            "all_day" to mapOf("type" to "boolean", "description" to "true when no time of day is given"),
            "start" to str + ("description" to "HH:MM, 24-hour; empty when all-day"),
            "end" to str + ("description" to "HH:MM, 24-hour; empty when all-day or not stated"),
            "repeat" to mapOf("type" to "string", "enum" to listOf("none", "daily", "weekly")),
            "days" to mapOf(
                "type" to "array",
                "description" to "weekly repeats: the weekdays; weekdays = MO..FR",
                "items" to mapOf("type" to "string", "enum" to listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")),
            ),
        )
        return BetaJsonOutputFormat.Schema.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(properties))
            .putAdditionalProperty("required", JsonValue.from(properties.keys.toList()))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build()
    }

    companion object {
        const val MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"

        private val SYSTEM = """
            You turn a short note someone typed into a calendar event for their phone's calendar.
            Work out the title, the date, the start and end time, whether it is all-day, and how it repeats.
            - Resolve relative dates ("tomorrow", "Fri", "next Tuesday") from today's date. A bare weekday means its next occurrence, today included.
            - "8-9pm" means 20:00 to 21:00. A start with no end and no duration: leave end empty.
            - No time of day at all: all_day true, start and end empty.
            - "every Mon/Thu" is weekly on MO and TH; "weekdays" is weekly MO..FR; "every day"/"daily" is daily. Otherwise repeat is none and days is empty.
            - For a weekly repeat, date is the first matching day on or after the date that applies.
            - Keep the title in the person's own words (e.g. "Lunch with Blake"), without the date, time or repeat words.
            If the text doesn't describe an event, set understood to false.
        """.trimIndent()
    }
}
