package io.github.iamth3doctor.searchlight.calendar

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.iamth3doctor.searchlight.data.SecretStore
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Typed text to an [EventDraft]: with Claude when an API key is set ([ClaudeEventParser]), else
 * (or when the call fails: offline, a bad key, a refusal, output that doesn't check out) with
 * the on-device [RuleParser]. The only network call is to the Anthropic API, and only with a key.
 */
class EventParser(private val secrets: SecretStore, private val claude: ClaudeEventParser = ClaudeEventParser()) {

    enum class Source { AI, RULES }

    /** [draft] null: not understood. */
    class Result(val draft: EventDraft?, val source: Source)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "event-parser").apply { isDaemon = true } }

    /** Tests: stands in for the Claude call (returns its JSON, or throws like a failed call). */
    @Volatile var aiOverride: ((text: String) -> String)? = null

    /** What the last parse used (tests). */
    @Volatile var lastSource: Source? = null
        private set

    var apiKey: String?
        get() = secrets.get(SecretStore.AI_KEY)
        set(value) = secrets.put(SecretStore.AI_KEY, value?.trim())

    val hasKey get() = secrets.has(SecretStore.AI_KEY)

    fun parse(text: String, today: LocalDate, defaultDate: LocalDate, zone: ZoneId, done: (Result) -> Unit) {
        worker.execute {
            val result = parseNow(text, today, defaultDate, zone)
            lastSource = result.source
            main.post { done(result) }
        }
    }

    private fun parseNow(text: String, today: LocalDate, defaultDate: LocalDate, zone: ZoneId): Result {
        val override = aiOverride
        val key = if (override == null) apiKey else null
        if (override != null || key != null) {
            try {
                val json = override?.invoke(text) ?: claude.parse(key!!, text, today, defaultDate, zone)
                AiEventJson.toDraft(json)?.let { return Result(it, Source.AI) }
            } catch (e: Exception) {
                // The class only: messages can carry request details, and nothing about the key is logged.
                Log.w(TAG, "AI parsing failed (${e.javaClass.simpleName}); using on-device rules")
            }
        }
        return Result(RuleParser.parse(text, today, defaultDate), Source.RULES)
    }

    /** Settings' Test button: checks [key] with the provider; [done](null) when it works, else what went wrong. */
    fun test(key: String, done: (String?) -> Unit) {
        worker.execute {
            val error = try {
                claude.test(key.trim())
                null
            } catch (e: com.anthropic.errors.UnauthorizedException) {
                "unauthorized"
            } catch (e: com.anthropic.errors.PermissionDeniedException) {
                "unauthorized"
            } catch (e: com.anthropic.errors.AnthropicServiceException) {
                "service"
            } catch (e: Exception) {
                "network"
            }
            main.post { done(error) }
        }
    }

    private companion object {
        const val TAG = "EventParser"
    }
}
