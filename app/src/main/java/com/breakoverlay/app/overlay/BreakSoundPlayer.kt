package com.breakoverlay.app.overlay

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Plays one short system notification chime for an overlay. It requests transient audio
 * focus, releases the MediaPlayer and abandons focus when the overlay is removed.
 * It cannot force sound when the device is muted, in a restrictive DND mode, or the
 * notification sound is configured to silent.
 */
class BreakSoundPlayer(private val context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var mediaPlayer: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var started = false
    private val stopRunnable = Runnable { release() }

    fun play() {
        if (started) return
        started = true
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        if (uri == null) {
            release()
            return
        }
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (!requestFocus(attributes)) {
            release()
            return
        }

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(context, uri)
            player.setOnPreparedListener {
                try {
                    it.start()
                    handler.postDelayed(stopRunnable, MAX_PLAY_MS)
                } catch (error: IllegalStateException) {
                    Log.w(TAG, "Notification chime could not start", error)
                    release()
                }
            }
            player.setOnCompletionListener { release() }
            player.setOnErrorListener { _, what, extra ->
                Log.w(TAG, "Notification chime error: what=$what extra=$extra")
                release()
                true
            }
            player.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to prepare notification chime", error)
            release()
        }
    }

    fun release() {
        handler.removeCallbacks(stopRunnable)
        val player = mediaPlayer
        mediaPlayer = null
        if (player != null) {
            try {
                player.setOnPreparedListener(null)
                player.setOnCompletionListener(null)
                player.setOnErrorListener(null)
                if (player.isPlaying) player.stop()
            } catch (_: IllegalStateException) {
                // Player had not reached a valid state; release is still required.
            } finally {
                try {
                    player.reset()
                    player.release()
                } catch (_: RuntimeException) {
                    // Nothing else to release.
                }
            }
        }
        abandonFocus()
    }

    private fun requestFocus(attributes: AudioAttributes): Boolean {
        val manager = audioManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setOnAudioFocusChangeListener { }
                    .setWillPauseWhenDucked(true)
                    .build()
                focusRequest = request
                manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                manager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun abandonFocus() {
        val manager = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { manager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                manager.abandonAudioFocus(null)
            }
        } catch (_: RuntimeException) {
            // Audio focus is best-effort.
        }
        focusRequest = null
    }

    companion object {
        private const val TAG = "BreakSoundPlayer"
        private const val MAX_PLAY_MS = 6_000L
    }
}
