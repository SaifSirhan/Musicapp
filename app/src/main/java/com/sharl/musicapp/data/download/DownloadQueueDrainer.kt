package com.sharl.musicapp.data.download

import com.sharl.musicapp.data.DownloadJob

enum class DrainVerdict { PROCEEDED, BLOCKED }

/**
 * Consumes durable queue rows one at a time, owned by whatever worker survives the UI.
 * A blocked job ends the drain so a full volume cannot spin the queue; a failed job
 * leaves the rows behind it advancing. `transfer` must either move the row out of the
 * resumable set or report [DrainVerdict.BLOCKED].
 */
class DownloadQueueDrainer(
    private val store: DownloadJobStore,
    private val recover: suspend () -> Unit,
    private val transfer: suspend (DownloadJob) -> DrainVerdict
) {
    /** True when nothing resumable remains. */
    suspend fun drain(): Boolean {
        recover()
        while (true) {
            val next = store.resumable().firstOrNull() ?: return true
            if (transfer(next) == DrainVerdict.BLOCKED) return false
        }
    }
}
