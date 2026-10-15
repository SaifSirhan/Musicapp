package com.sharl.musicapp

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

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

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(msFactory)
            .build()

        player.addListener(object : Player.Listener {
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

        mediaSession = MediaSession.Builder(this, player).build()

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this).build()
        notificationProvider.setSmallIcon(R.drawable.ic_music_note)
        setMediaNotificationProvider(notificationProvider)

        Log.d("CTRL", "PlaybackService created, MediaSession built")
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.player?.release()
        mediaSession?.release()
        mediaSession = null
        Log.d("CTRL", "PlaybackService destroyed, player and session released")
        super.onDestroy()
    }
}
