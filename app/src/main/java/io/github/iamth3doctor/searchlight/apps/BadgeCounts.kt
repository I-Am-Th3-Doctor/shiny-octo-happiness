package io.github.iamth3doctor.searchlight.apps

/**
 * Unread counts per app from the posted notifications. Pure; JVM-tested. A notification
 * counts unless it's ongoing (music playing, a download, a foreground service: not "unread")
 * or a group summary (it stands for notifications already counted one by one).
 */
object BadgeCounts {

    class Posted(val packageName: String, val ongoing: Boolean, val groupSummary: Boolean)

    fun count(posted: List<Posted>): Map<String, Int> =
        posted.asSequence()
            .filter { !it.ongoing && !it.groupSummary }
            .groupingBy { it.packageName }
            .eachCount()
}
