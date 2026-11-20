package com.childsplay.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings

/**
 * Builds a first-run home board so the launcher is useful the moment it's set as home:
 * a cloud for talking, a TV for watching, a painting for making, a statue for essentials,
 * and a balloon for games, filled from the apps' own Play categories and default handlers.
 */
object Seeder {

    fun seed(context: Context, apps: List<AppEntry>): HomeState {
        val pm = context.packageManager
        fun defaultPkg(intent: Intent): String? = runCatching {
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        }.getOrNull()?.takeIf { it != "android" }

        val dialer = defaultPkg(Intent(Intent.ACTION_DIAL))
        val sms = defaultPkg(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:")))
        val email = defaultPkg(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")))
        val camera = defaultPkg(Intent(MediaStore.ACTION_IMAGE_CAPTURE))
        val browser = defaultPkg(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")))
        val settings = defaultPkg(Intent(Settings.ACTION_SETTINGS))
        val mainApps = apps.filter { !it.isWorkProfile }

        val used = HashSet<String>()
        fun take(predicate: (AppEntry) -> Boolean, limit: Int = 12): List<String> =
            mainApps.filter { it.key !in used && predicate(it) }.take(limit).map { it.key }.also { used += it }

        fun pkgIs(vararg pkgs: String?) = { a: AppEntry -> pkgs.any { it != null && a.packageName == it } }
        fun nameHas(vararg words: String) = { a: AppEntry -> words.any { a.label.contains(it, ignoreCase = true) } }

        val talk = take(pkgIs(dialer, sms, email)) +
            take({ it.category == ApplicationInfo.CATEGORY_SOCIAL }) +
            take(nameHas("Contacts", "Messages", "Gmail", "Chat", "WhatsApp", "Signal", "Telegram", "Discord"))
        val watch = take({
            it.category == ApplicationInfo.CATEGORY_VIDEO || it.category == ApplicationInfo.CATEGORY_AUDIO
        }) + take(nameHas("YouTube", "Netflix", "Spotify", "Music", "Podcast", "TV"))
        val make = take(pkgIs(camera)) +
            take({ it.category == ApplicationInfo.CATEGORY_IMAGE || it.category == ApplicationInfo.CATEGORY_PRODUCTIVITY }) +
            take(nameHas("Photos", "Gallery", "Docs", "Keep", "Notes", "Drive"))
        val games = take({ it.category == ApplicationInfo.CATEGORY_GAME }, limit = 20)
        val essentials = take(pkgIs(browser, settings, context.packageName)) +
            take({ it.category == ApplicationInfo.CATEGORY_MAPS || it.category == ApplicationInfo.CATEGORY_NEWS }) +
            take(nameHas("Clock", "Calculator", "Calendar", "Files", "Play Store", "Weather", "Wallet"))

        val things = listOfNotNull(
            Thing(HomeStore.newId(), "Chatter", ThingShape.CLOUD, 6, talk),
            Thing(HomeStore.newId(), "Watch & Listen", ThingShape.TV, 0, watch),
            Thing(HomeStore.newId(), "Make Stuff", ThingShape.PAINTING, 9, make),
            Thing(HomeStore.newId(), "Essentials", ThingShape.STATUE, 8, essentials),
            if (games.isNotEmpty()) Thing(HomeStore.newId(), "Games", ThingShape.BALLOON, 1, games) else null,
        )
        val items = buildList {
            add(HomeItem.Weather)
            add(HomeItem.StickyNote)
            things.forEach { add(HomeItem.ThingItem(it.id)) }
        }
        return HomeState(items = items, things = things.associateBy { it.id }, room = 0, seeded = true)
    }
}
