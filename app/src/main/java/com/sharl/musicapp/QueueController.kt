package com.sharl.musicapp

import android.os.Bundle
import android.os.Handler
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executor

/** Thin timeline observer. The service owns all queue writes and local resolution. */
class QueueController(private val controller: MediaController) {
    private val mutableQueue = MutableStateFlow<List<QueueItem>>(emptyList())
    private val mutableCurrent = MutableStateFlow<String?>(null)
    private val mutableErrors = MutableStateFlow<String?>(null)
    val queue: StateFlow<List<QueueItem>> = mutableQueue.asStateFlow()
    val currentUid: StateFlow<String?> = mutableCurrent.asStateFlow()
    val errors: StateFlow<String?> = mutableErrors.asStateFlow()
    private var closed = false
    private val handler = Handler(controller.applicationLooper)
    private val executor = Executor { handler.post(it) }
    private val listener = object : Player.Listener {
        override fun onTimelineChanged(timeline: Timeline, reason: Int) = refresh()
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = refresh()
    }

    init {
        if (android.os.Looper.myLooper() == controller.applicationLooper) {
            controller.addListener(listener)
            refresh()
        } else {
            handler.post {
                if (!closed) {
                    controller.addListener(listener)
                    refresh()
                }
            }
        }
    }

    private fun refresh() {
        if (closed) return
        mutableQueue.value = (0 until controller.mediaItemCount).map {
            QueueProtocol.fromMediaItem(controller.getMediaItemAt(it))
        }
        mutableCurrent.value = controller.currentMediaItem?.mediaId
    }

    fun replace(items: List<QueueItem>, startUid: String) = send("replace", items, startUid)
    fun append(items: List<QueueItem>) = send("append", items)
    fun playNext(videoId: String) = insertNext(QueueItem(videoId, ""))
    fun insertNext(item: QueueItem) = send("insertNext", listOf(item))
    fun moveNext(uid: String) = send("moveNext", uid = uid)
    fun remove(uid: String) = send("remove", uid = uid)
    fun clearUpcoming() = send("clearUpcoming")
    fun clear() = send("clear")
    fun play(uid: String) = send("play", uid = uid)
    fun close() {
        if (android.os.Looper.myLooper() != controller.applicationLooper) {
            handler.post { close() }
            return
        }
        closed = true
        controller.removeListener(listener)
    }

    private fun send(operation: String, items: List<QueueItem> = emptyList(), uid: String = "") {
        if (android.os.Looper.myLooper() != controller.applicationLooper) {
            val snapshot = items.toList()
            handler.post { send(operation, snapshot, uid) }
            return
        }
        if (closed) return
        mutableErrors.value = null
        val args = Bundle().apply {
            putString("operation", operation)
            putString("uid", uid)
            putParcelableArrayList("items", ArrayList(items.map(QueueProtocol::encode)))
        }
        try {
            val future = controller.sendCustomCommand(QueueProtocol.command, args)
            future.addListener({
                if (!closed) {
                    mutableErrors.value = runCatching {
                        val result = future.get()
                        result.extras.getString("error") ?: if (result.resultCode != SessionResult.RESULT_SUCCESS)
                            "Queue command could not be completed" else null
                    }.getOrDefault("Queue command could not be completed")
                }
            }, executor)
        } catch (_: Exception) { mutableErrors.value = "Queue command could not be completed" }
    }
}

internal object QueueProtocol {
    val command = SessionCommand("com.sharl.musicapp.QUEUE", Bundle.EMPTY)
    fun encode(item: QueueItem) = Bundle().apply {
        putString("videoId", item.videoId); putString("uid", item.uid)
        putString("title", item.title); putString("artist", item.artist)
        putString("localUri", item.localUri); putBoolean("isVideo", item.isVideo)
    }
    fun decode(bundle: Bundle) = QueueItem(
        bundle.getString("videoId").orEmpty(), bundle.getString("title").orEmpty(),
        bundle.getString("artist").orEmpty(), bundle.getString("localUri"),
        bundle.getBoolean("isVideo"), bundle.getString("uid").orEmpty(),
    )
    fun toMediaItem(item: QueueItem, uri: android.net.Uri) = MediaItem.Builder()
        .setMediaId(item.uid).setUri(uri)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist)
            .setExtras(encode(item.copy(localUri = item.localUri?.takeIf { it.startsWith("content://") }))).build()).build()
    fun fromMediaItem(item: MediaItem): QueueItem {
        val extras = item.mediaMetadata.extras ?: item.requestMetadata.extras ?: Bundle.EMPTY
        val videoId = extras.getString("videoId") ?: extras.getString("video_id")
            ?: item.mediaId.substringBeforeLast('|')
        val uid = extras.getString("uid") ?: item.mediaId.takeIf { it != videoId }.orEmpty()
        val contentUri = (item.requestMetadata.mediaUri ?: item.localConfiguration?.uri)
            ?.takeIf { it.scheme == "content" }?.toString()
        return QueueItem(videoId,
            item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty(),
            extras.getString("localUri") ?: contentUri, extras.getBoolean("isVideo"), uid)
    }
}
