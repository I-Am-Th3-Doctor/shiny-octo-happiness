package io.github.iamth3doctor.searchlight.data

/**
 * A web search: a [label] for the pill ("Search Ecosia for …") and a URL [template] with one %s
 * where the query goes. Presets come from [WebEngine]; Custom from what the person saved.
 */
data class WebTarget(val label: String, val template: String) {
    fun url(query: String): String = WebSearchUrl.build(template, query)
}

/** Checking and filling a search URL template. Pure; JVM-tested. Never touches the network. */
object WebSearchUrl {

    enum class Problem { NAME_MISSING, NOT_HTTPS, NO_PLACEHOLDER, MANY_PLACEHOLDERS }

    private const val PLACEHOLDER = "%s"

    /** What's wrong with a custom engine (name first, then URL), or null when it can be saved. */
    fun check(name: String, template: String): Problem? = when {
        name.isBlank() -> Problem.NAME_MISSING
        else -> checkUrl(template)
    }

    fun checkUrl(template: String): Problem? {
        val t = template.trim()
        val count = t.windowed(PLACEHOLDER.length).count { it == PLACEHOLDER }
        return when {
            !t.startsWith("https://", ignoreCase = true) || t.length <= "https://".length -> Problem.NOT_HTTPS
            count == 0 -> Problem.NO_PLACEHOLDER
            count > 1 -> Problem.MANY_PLACEHOLDERS
            else -> null
        }
    }

    /**
     * [template] with the query, URL-encoded, where %s is. Only %s is replaced, so other % codes
     * in a custom URL (say %20) stay as they are.
     */
    fun build(template: String, query: String): String =
        template.trim().replace(PLACEHOLDER, java.net.URLEncoder.encode(query, "UTF-8"))
}
