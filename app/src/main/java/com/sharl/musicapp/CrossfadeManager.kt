package com.sharl.musicapp

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * True overlapping crossfade built on two ExoPlayer instances.
 *
 * Player A is the default active player (the MediaSession is attached to it).
 * [standby] is prepared with the already-resolved next MediaItem while the
 * current track is still playing; at `duration - crossfade` both players run
 * and their volumes are ramped every 50 ms. When the ramp finishes, the old
 * player is paused and the session is moved to the new player.
 *
 * The manager never resolves media itself: the session timeline already holds
 * every upcoming local track, so a fade only needs a prepared standby engine.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class CrossfadeManager(
    private val scope: CoroutineScope,
    playerA: ExoPlayer,
    playerB: ExoPlayer,
    private val crossfadeDurationMs: () -> Long,
    private val onActivePlayerChanged: (newActive: ExoPlayer, oldActive: ExoPlayer) -> Unit,
) {

    var active: ExoPlayer = playerA
        private set

    var standby: ExoPlayer = playerB
        private set

    private var fading = false
    private var loopJob: Job? = null
    private var fadeJob: Job? = null
    private var preparedMediaId: String? = null

    val isFading: Boolean get() = fading

    init {
        attach(playerA)
        attach(playerB)
    }

    fun start() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    Log.w(TAG, "tick failed", t)
                }
            }
        }
    }

    fun release() {
        loopJob?.cancel()
        loopJob = null
        fadeJob?.cancel()
        fadeJob = null
        fading = false
        preparedMediaId = null
    }

    /** Called when the crossfade preference changes. */
    fun onSettingsChanged() {
        if (crossfadeDurationMs() <= 0L) {
            if (fading) abortFade("disabled") else resetStandby()
        }
    }

    private fun attach(player: ExoPlayer) {
        player.addListener(object : Player.Listener {
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (player === active && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED)
                    abortFade("timeline edited")
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (player === active) abortFade("shuffle changed")
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                if (player === active) abortFade("repeat changed")
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player !== active) return
                if (fading) {
                    // User tapped next/prev or picked another track mid-fade.
                    abortFade("manual transition")
                } else {
                    resetStandby()
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (player === active && fading && reason == Player.DISCONTINUITY_REASON_SEEK) {
                    abortFade("seek during fade")
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (player !== active || !fading) return
                if (playWhenReady) standby.play() else standby.pause()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player === active || !fading) return
                // A standby that ran out of audio (very short track) cannot be
                // faded: fall back to the active engine's own transition.
                if (playbackState == Player.STATE_ENDED) abortFade("standby ended")
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (player === active) return
                // A broken standby must never be faded in.
                if (fading) abortFade("standby error") else resetStandby()
            }
        })
    }

    private fun tick() {
        if (fading) return
        val configured = crossfadeDurationMs()
        if (configured <= 0L) {
            if (preparedMediaId != null) resetStandby()
            return
        }
        val player = active
        if (player.playbackState != Player.STATE_READY || !player.isPlaying) return
        val duration = player.duration
        if (duration == C.TIME_UNSET || duration <= 0L) return
        val position = player.currentPosition
        val nextIndex = player.nextMediaItemIndex
        val next = if (nextIndex >= 0) player.getMediaItemAt(nextIndex) else null

        // The queue changed under us (play-next insertion, skip): drop stale prep.
        if (preparedMediaId != null && next?.mediaId != preparedMediaId) resetStandby()

        // Repeat-one and a one-item repeat-all stay on the active engine.
        if (next == null || player.repeatMode == Player.REPEAT_MODE_ONE ||
            nextIndex == player.currentMediaItemIndex) return
        val fadeStart = duration - configured

        // Buffer the next track a little ahead so the fade is not silent.
        if (position >= fadeStart - PREPARE_LEAD_MS && preparedMediaId != next.mediaId) {
            prepareStandby(next)
        }

        if (position < fadeStart) return
        if (standby.playbackState != Player.STATE_READY || standby.mediaItemCount == 0) {
            // Still buffering: wait for the ticker. If it never becomes ready,
            // the current playlist advances normally (hard cut, no error).
            return
        }
        val remaining = duration - position
        if (remaining < MIN_FADE_MEDIA_MS) return
        beginFade(next.mediaId, minOf(configured, remaining))
    }

    /** Clone the entire timeline, including history and the actual shuffle traversal. */
    private fun prepareStandby(next: MediaItem) {
        val player = active
        val nextIndex = player.nextMediaItemIndex
        if (nextIndex < 0) return
        val playlist = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
        val order = mutableListOf<Int>()
        var index = player.currentTimeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET) {
            order.add(index)
            index = player.currentTimeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        runCatching {
            standby.volume = 0f
            standby.playWhenReady = false
            standby.setMediaItems(playlist, nextIndex, 0L)
            standby.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(order.toIntArray(), 0L))
            standby.shuffleModeEnabled = player.shuffleModeEnabled
            standby.repeatMode = player.repeatMode
            standby.playbackParameters = player.playbackParameters
            standby.prepare()
            preparedMediaId = next.mediaId
            Log.d(TAG, "standby prepared for ${next.mediaId} (${playlist.size} items)")
        }.onFailure {
            Log.w(TAG, "standby prepare failed", it)
            resetStandby()
        }
    }

    private fun beginFade(nextMediaId: String?, mediaFadeMs: Long) {
        val speed = active.playbackParameters.speed.coerceAtLeast(0.1f)
        // The configured duration is media time; at 2x it elapses twice as fast.
        val fadeMs = (mediaFadeMs / speed).toLong().coerceAtLeast(MIN_FADE_WALL_MS)
        fading = true
        standby.volume = 0f
        standby.playWhenReady = true
        standby.play()
        Log.d(
            TAG,
            "fade started: ${active.currentMediaItem?.mediaId} -> " +
                "${standby.currentMediaItem?.mediaId} over ${fadeMs}ms"
        )

        fadeJob = scope.launch {
            val start = SystemClock.elapsedRealtime()
            var pausedAt = 0L
            var pausedTotal = 0L
            while (isActive) {
                delay(RAMP_STEP_MS)
                if (!fading) return@launch
                val now = SystemClock.elapsedRealtime()
                if (!active.playWhenReady) {
                    if (standby.playWhenReady) standby.pause()
                    if (pausedAt == 0L) pausedAt = now
                    continue
                }
                if (pausedAt != 0L) {
                    pausedTotal += now - pausedAt
                    pausedAt = 0L
                    if (!standby.playWhenReady) standby.play()
                }
                val elapsed = now - start - pausedTotal
                val progress = (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
                active.volume = 1f - progress
                standby.volume = progress
                // Finish ~120 ms of media time early so the old player is
                // paused before it can auto-advance into the next item.
                if (elapsed >= fadeMs - FINISH_EARLY_MS) break
            }
            completeFade(nextMediaId)
        }
    }

    private fun completeFade(nextMediaId: String?) {
        if (!fading) return
        fading = false
        fadeJob = null
        val old = active
        val next = standby

        old.volume = 0f
        old.pause()
        next.volume = 1f
        next.playWhenReady = true

        active = next
        standby = old
        preparedMediaId = null

        runCatching { onActivePlayerChanged(next, old) }
            .onFailure { Log.e(TAG, "session handoff failed", it) }
        runCatching { old.stop() }
        runCatching { old.clearMediaItems() }
        old.volume = 0f
        old.playWhenReady = false

        Log.d(TAG, "fade complete, target=$nextMediaId active=${next.currentMediaItem?.mediaId}")
    }

    private fun abortFade(reason: String) {
        if (!fading) {
            resetStandby()
            return
        }
        Log.d(TAG, "fade aborted: $reason")
        fading = false
        fadeJob?.cancel()
        fadeJob = null
        runCatching {
            standby.pause()
            standby.volume = 0f
        }
        active.volume = 1f
        resetStandby()
    }

    private fun resetStandby() {
        if (fading) return
        runCatching {
            standby.pause()
            standby.clearMediaItems()
            standby.volume = 0f
        }
        preparedMediaId = null
    }

    private companion object {
        const val TAG = "CROSSFADE"
        const val TICK_MS = 100L
        const val RAMP_STEP_MS = 50L
        const val PREPARE_LEAD_MS = 5_000L
        const val MIN_FADE_MEDIA_MS = 300L
        const val MIN_FADE_WALL_MS = 250L
        const val FINISH_EARLY_MS = 120L
    }
}
