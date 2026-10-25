package io.github.iamth3doctor.searchlight.iconpack

/**
 * An icon pack's appfilter.xml: which drawable themes which app, plus the layers for apps it
 * doesn't theme. Built from the file's elements by [parse]; pure, so it's JVM-tested.
 */
class AppFilter(
    /** "package/fully.qualified.Class" -> drawable name. */
    val components: Map<String, String>,
    /** Calendar apps: component -> drawable prefix; today's icon is prefix + day of month (1-31). */
    val calendars: Map<String, String>,
    val backs: List<String>,
    val mask: String?,
    val upon: String?,
    /** How big the app's own icon is drawn inside the pack's layers (1 = full size). */
    val scale: Float,
) {
    /** Does the pack restyle apps it has no icon for? */
    val stylesFallbacks: Boolean get() = backs.isNotEmpty() || mask != null || upon != null || scale != 1f

    /** One element of the file: its tag and attributes. */
    class Element(val name: String, val attrs: Map<String, String>)

    companion object {
        /** @throws IllegalArgumentException when it themes nothing at all (not a usable pack). */
        fun parse(elements: Sequence<Element>): AppFilter {
            val components = HashMap<String, String>()
            val calendars = HashMap<String, String>()
            val backs = ArrayList<String>()
            var mask: String? = null
            var upon: String? = null
            var scale = 1f
            for (e in elements) when (e.name) {
                "item" -> {
                    val c = component(e.attrs["component"]) ?: continue
                    val d = e.attrs["drawable"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    components.putIfAbsent(c, d)
                }
                "calendar" -> {
                    val c = component(e.attrs["component"]) ?: continue
                    val p = e.attrs["prefix"]?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    calendars.putIfAbsent(c, p)
                }
                "iconback" -> backs += images(e)
                "iconmask" -> mask = images(e).firstOrNull() ?: mask
                "iconupon" -> upon = images(e).firstOrNull() ?: upon
                "scale" -> e.attrs["factor"]?.trim()?.toFloatOrNull()?.takeIf { it > 0f && it <= 2f }?.let { scale = it }
            }
            require(components.isNotEmpty() || calendars.isNotEmpty() || backs.isNotEmpty() || mask != null) {
                "appfilter themes nothing"
            }
            return AppFilter(components, calendars, backs, mask, upon, scale)
        }

        /** img1, img2, ... in order. */
        private fun images(e: Element) = e.attrs.entries
            .filter { it.key.startsWith("img") && it.value.isNotBlank() }
            .sortedBy { it.key.removePrefix("img").toIntOrNull() ?: Int.MAX_VALUE }
            .map { it.value.trim() }

        /**
         * "ComponentInfo{pkg/cls}" (or bare "pkg/cls"; ".Cls" is short for "pkg.Cls") ->
         * "pkg/fully.qualified.Cls". Null when it isn't one.
         */
        fun component(raw: String?): String? {
            val s = raw?.trim()?.removePrefix("ComponentInfo{")?.removeSuffix("}")?.trim() ?: return null
            val slash = s.indexOf('/')
            if (slash <= 0 || slash == s.lastIndex) return null
            val pkg = s.substring(0, slash).trim()
            val cls = s.substring(slash + 1).trim().let { if (it.startsWith(".")) pkg + it else it }
            return "$pkg/$cls"
        }

        /** Drawable names a pack might use for an app it has no appfilter entry for. */
        fun nameCandidates(pkg: String, cls: String): List<String> =
            listOf(pkg, cls).map { it.lowercase().replace('.', '_').replace('$', '_') }.distinct()
    }
}
