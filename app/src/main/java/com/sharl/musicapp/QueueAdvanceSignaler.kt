package com.sharl.musicapp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * In-process bridge between [PlaybackService] (which owns the ExoPlayer) and
 * the UI (which owns the display queue). With gapless playback the player holds
 * a real playlist of MediaItems, so it advances on its own and reports:
 *  - [trackChanged]: the player transitioned to a MediaItem tagged with a queue
 *    index (native auto-advance or an explicit seek). Carries that queue index.
 *  - [queueEnded]: the player reached STATE_ENDED with nothing left to play,
 *    i.e. the loaded window is exhausted and the UI must load the next batch.
 */
object QueueAdvanceSignaler {
    // Buffered generously so a burst of rapid skips (each native transition
    // emits one index) can't drop an update and leave the UI index stale.
    private val _trackChanged = MutableSharedFlow<Int>(extraBufferCapacity = 32)
    val trackChanged: SharedFlow<Int> = _trackChanged.asSharedFlow()

    private val _queueEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queueEnded: SharedFlow<Unit> = _queueEnded.asSharedFlow()

    fun notifyTrackChanged(newIndex: Int) { _trackChanged.tryEmit(newIndex) }
    fun notifyQueueEnded() { _queueEnded.tryEmit(Unit) }

    // Legacy support — kept for any remaining callers during the transition.
    private var listener: (() -> Unit)? = null
    fun setListener(l: (() -> Unit)?) { listener = l }
    fun notifyTrackEnded() { listener?.invoke() }
}
