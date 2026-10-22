package com.sharl.musicapp

import android.content.Context
import android.content.SharedPreferences
import android.media.audiofx.LoudnessEnhancer
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.sharl.musicapp.data.DeezerDataSourceFactory
import com.sharl.musicapp.data.RoutingDataSourceFactory
import com.sharl.musicapp.data.SettingsRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Owns two ExoPlayer instances so tracks can overlap for a true crossfade.
 * [playerA] is the session player by default; [CrossfadeManager] promotes
 * [playerB] once a fade completes. Both use USAGE_MEDIA/CONTENT_TYPE_MUSIC and
 * only the active player handles audio focus, so the standby player's stream
 * is never ducked (or paused by a focus loss) while it is being faded in.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var playerA: ExoPlayer? = null
    private var playerB: ExoPlayer? = null
    private var crossfadeManager: CrossfadeManager? = null
    private val enhancers = HashMap<ExoPlayer, LoudnessEnhancer>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings by lazy { SettingsRepo(this) }
    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    // The UI writes preferences through SettingsRepo; watching the same file
    // here applies speed/crossfade/normalisation to already-running players.
    private val settingsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "playback_speed", "crossfade_seconds", "normalize_volume" ->
                    applyPlaybackSettings()
            }
        }

    override fun onCreate() {
        super.onCreate()

        // Phase 36: Routing DataSource factory supporting both HTTP and Deezer streams
        val httpFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
        
        // Create routing factory that checks URI scheme
        val dsFactory = RoutingDataSourceFactory(this, httpFactory)
        val msFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(dsFactory)

        val exoA = ExoPlayer.Builder(this)
            .setMediaSourceFactory(msFactory)
            .setAudioAttributes(audioAttributes, true)
            .build()
        val exoB = ExoPlayer.Builder(this)
            .setMediaSourceFactory(msFactory)
            .setAudioAttributes(audioAttributes, false)
            .build()
        playerA = exoA
        playerB = exoB

        attachPlayer(exoA)
        attachPlayer(exoB)

        mediaSession = MediaSession.Builder(this, exoA).build()

        crossfadeManager = CrossfadeManager(
            scope = serviceScope,
            playerA = exoA,
            playerB = exoB,
            crossfadeDurationMs = { settings.crossfadeSeconds * 1_000L },
            onActivePlayerChanged = { newActive, old ->
                runCatching { mediaSession?.player = newActive }
                    .onFailure { Log.e("CROSSFADE", "MediaSession handoff failed", it) }
                // Only the active engine should own audio focus; the standby
                // would otherwise receive the LOSS that pauses the active one.
                runCatching { newActive.setAudioAttributes(audioAttributes, true) }
                    .onFailure { Log.w("CROSSFADE", "focus handoff failed", it) }
                runCatching { old.setAudioAttributes(audioAttributes, false) }
                    .onFailure { Log.w("CROSSFADE", "focus release failed", it) }
                applyPlaybackSettingsTo(newActive)
            },
            onTrackChanged = { index -> QueueAdvanceSignaler.notifyTrackChanged(index) },
            onPrefetchNext = { index -> QueueAdvanceSignaler.notifyPrefetchNext(index) }
        ).also { it.start() }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_music_note)
        setMediaNotificationProvider(notificationProvider)

        applyPlaybackSettings()
        prefs.registerOnSharedPreferenceChangeListener(settingsListener)

        Log.d("CTRL", "PlaybackService created with simple HTTP DataSource")
    }

    private fun attachPlayer(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(
                    "EXOPLAYER",
                    "player=${if (player === playerA) "A" else "B"} " +
                        "code=${error.errorCodeName} msg=${error.message}",
                    error
                )
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                if (player !== crossfadeManager?.active) return
                Log.d("ISLAND", "title=${mediaMetadata.title} artist=${mediaMetadata.artist}")
            }

            override fun onPlaybackStateChanged(state: Int) {
                // Only the active engine reports the end of the queue; the
                // standby is stopped during every handoff.
                if (state != Player.STATE_ENDED) return
                if (player !== crossfadeManager?.active) return
                if (crossfadeManager?.isFading == true) return
                QueueAdvanceSignaler.notifyQueueEnded()
                QueueAdvanceSignaler.notifyTrackEnded() // legacy
            }
        })
    }

    private fun applyPlaybackSettings() {
        val list = listOfNotNull(playerA, playerB)
        if (list.isEmpty()) return
        list.forEach { applyPlaybackSettingsTo(it) }
        crossfadeManager?.onSettingsChanged()
    }

    private fun applyPlaybackSettingsTo(p: ExoPlayer) {
        runCatching { p.setPlaybackSpeed(settings.playbackSpeed) }
            .onFailure { Log.w("SPEED", "Could not apply playback speed", it) }
        // Real crossfade replaces the Phase 22 silence-skip fallback.
        runCatching { p.setSkipSilenceEnabled(false) }
        applyVolumeNormalization(p)
    }

    private fun applyVolumeNormalization(p: ExoPlayer) {
        try {
            if (!settings.normalizeVolume) {
                enhancers[p]?.enabled = false
                return
            }
            val fx = enhancers[p] ?: LoudnessEnhancer(p.audioSessionId).also {
                it.setTargetGain(400) // +4 dB
                enhancers[p] = it
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
        crossfadeManager?.release()
        crossfadeManager = null
        enhancers.values.forEach { runCatching { it.release() } }
        enhancers.clear()
        mediaSession?.release()
        mediaSession = null
        playerA?.release()
        playerA = null
        playerB?.release()
        playerB = null
        serviceScope.cancel()
        Log.d("CTRL", "PlaybackService destroyed, dual players and session released")
        super.onDestroy()
    }
}
