package com.sharl.musicapp

object QueueAdvanceSignaler {
    private var listener: (() -> Unit)? = null

    fun setListener(l: (() -> Unit)?) { listener = l }

    fun notifyTrackEnded() {
        listener?.invoke()
    }
}
