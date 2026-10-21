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
 * The manager never resolves URLs itself: the UI owns extraction and is asked
 * to prefetch via [onPrefetchNext] when the next MediaItem is not loaded yet.
 * That keeps InnerTube/yt-dlp fallback logic in one place.
 */
class CrossfadeManager(
    private val scope: CoroutineScope,
    playerA: ExoPlayer,
    playerB: ExoPlayer,
    private val crossfadeDurationMs: () -> Long,
    private val onActivePlayerChanged: (newActive: ExoPlayer, oldActive: ExoPlayer) -> Unit,
    private val onTrackChanged: (Int) -> Unit,
    private val onPrefetchNext: (Int) -> Unit,
) {

    var active: ExoPlayer = playerA
        private set

    var standby: ExoPlayer = playerB
        private set

    private var fading = false
    private var loopJob: Job? = null
    private var fadeJob: Job? = null
    private var preparedMediaId: String? = null
    private var prefetchSentForTag: Int? = null

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
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player !== active) return
                if (fading) {
                    // User tapped next/prev or picked another track mid-fade.
                    abortFade("manual transition")
                } else {
                    resetStandby()
                }
                prefetchSentForTag = null
                val index = queueIndex(mediaItem)
                if (index >= 0) onTrackChanged(index)
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
        val tag = queueIndex(player.currentMediaItem)
        val nextIndex = player.nextMediaItemIndex
        val next = if (nextIndex >= 0) player.getMediaItemAt(nextIndex) else null

        // The queue changed under us (play-next insertion, skip): drop stale prep.
        if (preparedMediaId != null && next?.mediaId != preparedMediaId) resetStandby()

        // Resolve the next URL well before it is needed. The UI handles this
        // because it owns InnerTube/yt-dlp extraction.
        val lead = maxOf(RESOLVE_LEAD_MS, configured + 5_000L)
        if (tag >= 0 && position >= duration - lead && prefetchSentForTag != tag) {
            prefetchSentForTag = tag
            if (next == null) onPrefetchNext(tag + 1)
        }

        if (next == null) return
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
        beginFade(queueIndex(next), minOf(configured, remaining))
    }

    /**
     * Loads the resolved suffix of the active playlist (next track onwards)
     * into the standby player, muted and paused. Preparing here means only the
     * first few hundred KB still need to arrive when the fade starts.
     */
    private fun prepareStandby(next: MediaItem) {
        val player = active
        val currentIndex = player.currentMediaItemIndex
        if (currentIndex < 0) return
        val suffix = (currentIndex + 1 until player.mediaItemCount)
            .map { player.getMediaItemAt(it) }
        if (suffix.isEmpty()) return
        runCatching {
            standby.volume = 0f
            standby.playWhenReady = false
            standby.setMediaItems(suffix, 0, 0L)
            standby.prepare()
            preparedMediaId = next.mediaId
            Log.d(TAG, "standby prepared for ${next.mediaId} (${suffix.size} items)")
        }.onFailure {
            Log.w(TAG, "standby prepare failed", it)
            resetStandby()
        }
    }

    private fun beginFade(nextTag: Int, mediaFadeMs: Long) {
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
            completeFade(nextTag)
        }
    }

    private fun completeFade(nextTag: Int) {
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
        prefetchSentForTag = null

        runCatching { onActivePlayerChanged(next, old) }
            .onFailure { Log.e(TAG, "session handoff failed", it) }
        runCatching { old.stop() }
        runCatching { old.clearMediaItems() }
        old.volume = 0f
        old.playWhenReady = false

        if (nextTag >= 0) onTrackChanged(nextTag)
        Log.d(TAG, "fade complete, active=${next.currentMediaItem?.mediaId}")
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

    private fun queueIndex(item: MediaItem?): Int =
        item?.requestMetadata?.extras?.getInt("queue_index", -1) ?: -1

    private companion object {
        const val TAG = "CROSSFADE"
        const val TICK_MS = 100L
        const val RAMP_STEP_MS = 50L
        const val RESOLVE_LEAD_MS = 20_000L
        const val PREPARE_LEAD_MS = 5_000L
        const val MIN_FADE_MEDIA_MS = 300L
        const val MIN_FADE_WALL_MS = 250L
        const val FINISH_EARLY_MS = 120L
    }
}
