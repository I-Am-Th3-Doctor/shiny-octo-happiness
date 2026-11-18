package io.github.iamth3doctor.searchlight.weatherapp

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The scene's soft soundtrack, synthesized on the phone (no sound files): rain as a hiss with
 * patters of drops, wind as a low gusting rush, thunder as a rumble when lightning strikes.
 * Levels follow the scene ([rain], [wind], 0..1). Plays on the media stream, quietly; the
 * weather screen starts it only when sound is on and the phone isn't silenced or in Do Not Disturb.
 */
class WeatherSound {

    @Volatile var rain = 0f
    @Volatile var wind = 0f
    @Volatile private var thunderPending = 0

    /** Thunder claps asked for (tests). */
    var thunders = 0
        private set

    @Volatile private var running = false
    private var thread: Thread? = null

    val playing: Boolean get() = running

    fun thunder() {
        thunders++
        thunderPending++
    }

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "weather-sound").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.join(300)
        thread = null
    }

    private fun loop() {
        val rate = 22050
        val track = try {
            val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(maxOf(min, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "No audio", e)
            running = false
            return
        }
        val rnd = Random()
        val buf = ShortArray(1024)
        var rainAmp = 0.0
        var windAmp = 0.0
        var prev = 0.0
        var brown = 0.0
        var lp1 = 0.0
        var lp2 = 0.0
        var drop = 0.0
        var thunderEnv = 0.0
        var crack = 0.0
        var t = 0.0
        // Fade in so it never starts with a click.
        var master = 0.0
        try {
            track.play()
            while (running) {
                val rainTarget = rain * 0.22
                val windTarget = wind * 0.45
                if (thunderPending > 0) {
                    thunderPending = 0
                    thunderEnv = 1.0
                    crack = 1.0
                }
                for (i in buf.indices) {
                    t += 1.0 / rate
                    rainAmp += (rainTarget - rainAmp) * 0.0004
                    windAmp += (windTarget - windAmp) * 0.0002
                    master += (1.0 - master) * 0.0002
                    val n = rnd.nextDouble() * 2 - 1
                    // Rain: brightened noise, plus the odd nearby drop.
                    val hiss = n - prev * 0.7
                    prev = n
                    if (rnd.nextDouble() < rainAmp * 0.02) drop = 0.5 + rnd.nextDouble() * 0.5
                    drop *= 0.985
                    val rainS = rainAmp * (hiss * 0.6 + drop * n)
                    // Wind: brown noise, gusting slowly.
                    brown = (brown + n * 0.02) * 0.997
                    val gust = 0.55 + 0.45 * sin(2 * PI * 0.11 * t) * sin(2 * PI * 0.037 * t + 1.3)
                    val windS = windAmp * brown * 4 * gust
                    // Thunder: a sharp crack then a long, low rumble.
                    lp1 += (n - lp1) * 0.02
                    lp2 += (lp1 - lp2) * 0.02
                    thunderEnv *= exp(-1.0 / (2.8 * rate))
                    crack *= exp(-1.0 / (0.12 * rate))
                    val thunderS = thunderEnv * lp2 * 9 + crack * n * 0.25
                    val s = (rainS + windS + thunderS) * master * 0.7
                    buf[i] = (s.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
                }
                track.write(buf, 0, buf.size)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sound stopped", e)
        } finally {
            runCatching { track.pause(); track.flush(); track.release() }
            running = false
        }
    }

    private companion object {
        const val TAG = "WeatherSound"
    }
}
