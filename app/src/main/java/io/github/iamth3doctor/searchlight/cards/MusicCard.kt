package io.github.iamth3doctor.searchlight.cards

import android.app.Activity
import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationManagerCompat
import io.github.iamth3doctor.searchlight.R
import io.github.iamth3doctor.searchlight.media.MediaListenerService
import io.github.iamth3doctor.searchlight.media.MusicApps
import io.github.iamth3doctor.searchlight.searchlight
import io.github.iamth3doctor.searchlight.ui.Permissions

/**
 * Artwork, track, artist and controls from a media session.
 *
 * Bound to the default music player chosen in Settings (artwork, controls and tapping all
 * target that app, even before it has a session); with no default, it follows whichever app
 * is playing. [onStartedPlaying] fires when audio starts, so home can bring this card forward.
 */
class MusicCard(activity: Activity) : HomeCard(CardType.MUSIC, activity) {

    var onStartedPlaying: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val sessions = activity.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(activity, MediaListenerService::class.java)
    private val prefs = activity.searchlight.prefs
    private var controller: MediaController? = null
    private var wasPlaying = false

    /** The chosen default player, if one is set and still installed. */
    private fun defaultPackage(): String? =
        prefs.defaultMusicPackage?.takeIf { MusicApps.isInstalled(activity, it) }

    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var source: TextView
    private lateinit var prev: ImageButton
    private lateinit var playPause: ImageButton
    private lateinit var next: ImageButton

    override val layout = R.layout.card_music

    val isPlaying: Boolean get() = controller?.playbackState?.state == PlaybackState.STATE_PLAYING

    fun hasAccess() = NotificationManagerCompat.getEnabledListenerPackages(activity).contains(activity.packageName)

    override fun bind(root: View) {
        art = root.findViewById(R.id.musicArt)
        title = root.findViewById(R.id.musicTitle)
        artist = root.findViewById(R.id.musicArtist)
        source = root.findViewById(R.id.musicSource)
        prev = root.findViewById(R.id.musicPrev)
        playPause = root.findViewById(R.id.musicPlayPause)
        next = root.findViewById(R.id.musicNext)
        art.clipToOutline = true
        prev.setOnClickListener { controller?.transportControls?.skipToPrevious() }
        next.setOnClickListener { controller?.transportControls?.skipToNext() }
        playPause.setOnClickListener {
            val c = controller
            when {
                c != null -> if (isPlaying) c.transportControls.pause() else c.transportControls.play()
                defaultPackage() != null -> startDefaultPlayer(defaultPackage()!!)
            }
        }
        root.findViewById<View>(R.id.card).setOnClickListener { openPlayer() }
    }

    override fun onStart() {
        // Re-read the default each time home becomes visible: it may have changed in Settings.
        controller?.unregisterCallback(callback)
        controller = null
        if (hasAccess()) {
            try {
                sessions.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, main)
                pick(sessions.getActiveSessions(listenerComponent))
            } catch (e: SecurityException) {
                pick(emptyList())
            }
        }
        super.onStart()
    }

    override fun onStop() {
        try {
            sessions.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (e: Exception) { /* never registered */ }
        controller?.unregisterCallback(callback)
        controller = null
        super.onStop()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> pick(list.orEmpty()) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = stateChanged()
        override fun onMetadataChanged(metadata: MediaMetadata?) = render()
        override fun onSessionDestroyed() {
            controller?.unregisterCallback(this)
            controller = null
            render()
        }
    }

    /**
     * With a default player: only its session. Without: prefer the session that's actually
     * playing, otherwise the most recent one.
     */
    private fun pick(list: List<MediaController>) {
        val def = defaultPackage()
        val candidates = if (def != null) list.filter { it.packageName == def } else list
        val best = candidates.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: candidates.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        stateChanged()
    }

    private fun stateChanged() {
        val playing = isPlaying
        if (playing && !wasPlaying) onStartedPlaying?.invoke()
        wasPlaying = playing
        render()
    }

    override fun draw() {
        val c = controller
        val meta = c?.metadata
        val def = defaultPackage()
        if ((c == null || meta == null) && def != null) {
            // Default player chosen but not playing (or no media access yet): still show it,
            // and let play start it.
            art.scaleType = ImageView.ScaleType.FIT_CENTER
            art.setImageDrawable(runCatching { activity.packageManager.getApplicationIcon(def) }.getOrNull())
            title.text = MusicApps.label(activity, def)
            artist.setText(if (hasAccess()) R.string.music_default_idle else R.string.music_needs_access)
            source.text = ""
            prev.visibility = View.INVISIBLE
            next.visibility = View.INVISIBLE
            playPause.visibility = View.VISIBLE
            playPause.setImageResource(R.drawable.ic_play)
            return
        }
        if (c == null || meta == null) {
            art.setImageResource(R.drawable.ic_music)
            art.scaleType = ImageView.ScaleType.CENTER
            title.setText(R.string.music_nothing)
            artist.setText(if (hasAccess()) R.string.music_nothing_hint else R.string.music_needs_access)
            source.text = ""
            setControlsVisible(false)
            return
        }
        title.text = meta.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        artist.text = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        source.text = appLabel(c.packageName)
        val bmp: Bitmap? = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (bmp != null) {
            art.scaleType = ImageView.ScaleType.CENTER_CROP
            art.setImageBitmap(bmp)
        } else {
            art.scaleType = ImageView.ScaleType.FIT_CENTER
            art.setImageDrawable(runCatching { activity.packageManager.getApplicationIcon(c.packageName) }.getOrNull())
        }
        setControlsVisible(true)
        playPause.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun setControlsVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        prev.visibility = v
        playPause.visibility = v
        next.visibility = v
    }

    private fun appLabel(pkg: String): String = runCatching {
        activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Wake the default player with a PLAY key; if it hasn't started a session shortly after, open it. */
    private fun startDefaultPlayer(pkg: String) {
        MusicApps.sendMediaKey(activity, pkg, KeyEvent.KEYCODE_MEDIA_PLAY)
        main.postDelayed({
            if (started && controller == null) launchApp(pkg)
        }, 2500)
    }

    private fun launchApp(pkg: String) {
        activity.packageManager.getLaunchIntentForPackage(pkg)?.let {
            activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun openPlayer() {
        val c = controller
        if (c == null) {
            val def = defaultPackage()
            when {
                def != null -> launchApp(def)
                !hasAccess() -> Permissions.openNotificationListenerSettings(activity)
            }
            return
        }
        val pi = c.sessionActivity
        try {
            if (pi != null) {
                if (Build.VERSION.SDK_INT >= 34) {
                    val opts = ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    pi.send(activity, 0, null, null, null, null, opts.toBundle())
                } else {
                    pi.send()
                }
                return
            }
        } catch (e: Exception) { /* fall through to launching the app */ }
        launchApp(c.packageName)
    }
}
