package io.github.iamth3doctor.searchlight.apps

import android.content.ComponentName
import android.os.UserHandle
import io.github.iamth3doctor.searchlight.search.MatchKey

/** One launchable activity, in one user profile (personal or work). */
class AppEntry(
    val component: ComponentName,
    val user: UserHandle,
    val userSerial: Long,
    val label: String,
    /** Our own launcher entry; launching it opens settings instead. */
    val isSelf: Boolean,
) {
    /** Stable id used for favorites and launch counts. Survives reboots (serial, not UserHandle). */
    val key: String = "$userSerial/${component.flattenToString()}"

    val matchKey = MatchKey(label)

    val packageName: String get() = component.packageName

    override fun equals(other: Any?) = other is AppEntry && other.key == key
    override fun hashCode() = key.hashCode()
}
