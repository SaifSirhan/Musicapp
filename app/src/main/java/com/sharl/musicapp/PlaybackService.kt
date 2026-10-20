package com.sharl.musicapp

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sharl.musicapp.data.SettingsRepo

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private val settings by lazy { SettingsRepo(this) }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    // The UI writes preferences through SettingsRepo; watching the same file
    // here applies speed/crossfade/normalisation to an already-running player.
    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "playback_speed", "crossfade_seconds", "normalize_volume" ->
                    applyPlaybackSettings()
            }
        }

    override fun onCreate() {
        super.onCreate()

        // DataSource/MediaSource config copied verbatim from MainActivity.kt.
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                 "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent(ua)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        val dsFactory = androidx.media3.datasource.DefaultDataSource.Factory(
            this, httpFactory
        )
        val msFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsFactory)

        val exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(msFactory)
            .build()
        player = exoPlayer

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e("EXOPLAYER", "code=${error.errorCodeName} msg=${error.message}", error)
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                Log.d("ISLAND", "title=${mediaMetadata.title} artist=${mediaMetadata.artist}")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // The UI tags every MediaItem with its queue index in the
                // request-metadata extras so we can report exactly which track
                // the playlist advanced to (gapless auto-advance or seek).
                val idx = mediaItem?.requestMetadata?.extras?.getInt("queue_index", -1) ?: -1
                if (idx >= 0) {
                    QueueAdvanceSignaler.notifyTrackChanged(idx)
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // The loaded window is exhausted (no next item and repeat
                    // off). Tell the UI to load the next batch / handle repeat.
                    QueueAdvanceSignaler.notifyQueueEnded()
                    QueueAdvanceSignaler.notifyTrackEnded() // legacy
                }
            }
        })

        mediaSession = MediaSession.Builder(this, exoPlayer).build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_music_note)
        setMediaNotificationProvider(notificationProvider)

        applyPlaybackSettings()
        prefs.registerOnSharedPreferenceChangeListener(settingsListener)

        Log.d("CTRL", "PlaybackService created, MediaSession built")
    }

    private fun applyPlaybackSettings() {
        val p = player ?: return
        runCatching { p.setPlaybackSpeed(settings.playbackSpeed) }
            .onFailure { Log.w("SPEED", "Could not apply playback speed", it) }

        val crossfade = settings.crossfadeSeconds
        // media3 1.10.1 has no Player.setCrossfadeDurationMs (verified against
        // the cached AAR), so the spec's fallback is used: tighten the gaps by
        // skipping silence. The requested duration is logged for diagnostics.
        runCatching { p.setSkipSilenceEnabled(crossfade > 0) }
            .onFailure { Log.w("CROSSFADE", "Skip-silence fallback unavailable", it) }
        if (crossfade > 0) {
            Log.i(
                "CROSSFADE",
                "Requested ${crossfade}s crossfade — setCrossfadeDurationMs is not " +
                    "available in media3 1.10.1, using silence-skip fallback"
            )
        }

        applyVolumeNormalization(p)
    }

    private fun applyVolumeNormalization(p: ExoPlayer) {
        try {
            if (!settings.normalizeVolume) {
                loudnessEnhancer?.enabled = false
                return
            }
            val fx = loudnessEnhancer ?: LoudnessEnhancer(p.audioSessionId).also {
                it.setTargetGain(400) // +4 dB
                loudnessEnhancer = it
            }
            fx.enabled = true
            Log.d("AUDIO", "LoudnessEnhancer on, session=${p.audioSessionId}")
        } catch (e: Exception) {
            Log.w("AUDIO", "LoudnessEnhancer unavailable", e)
        }
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(settingsListener)
        loudnessEnhancer?.release()
        loudnessEnhancer = null
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        Log.d("CTRL", "PlaybackService destroyed, player and session released")
        super.onDestroy()
    }
}
