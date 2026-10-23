package com.sharl.musicapp

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** All custom mutations are ordered on the service main scope, including their IO resolution. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class LocalMediaSessionCallback(
    private val scope: CoroutineScope,
    private val resolve: suspend (QueueItem) -> QueueItem?,
) : MediaSession.Callback {
    private val mutations = Mutex()

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
        MediaSession.ConnectionResult.AcceptedResultBuilder(session)
            .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(QueueProtocol.command).build()).build()

    override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand, args: Bundle): ListenableFuture<SessionResult> = future {
        if (customCommand.customAction != QueueProtocol.command.customAction)
            return@future SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED)
        mutations.withLock {
            @Suppress("DEPRECATION")
            val items = args.getParcelableArrayList<Bundle>("items").orEmpty().map(QueueProtocol::decode)
            val operation = args.getString("operation")
            val uid = args.getString("uid").orEmpty()
            var missing = false
            when (operation) {
                "replace", "append", "insertNext" -> {
                    // Capture identities, never player indexes, before IO. A handoff can change the player.
                    val before = snapshot(session.player)
                    val input = normalize(items, if (operation == "replace") emptySet() else before.toSet())
                    val requested = input.getOrNull(items.indexOfFirst { it.uid == uid })?.uid
                    val resolved = input.mapNotNull { item -> resolve(item)?.let {
                        QueueProtocol.toMediaItem(it, Uri.parse(requireNotNull(it.localUri)))
                    } }
                    missing = resolved.size != input.size
                    check(snapshot(session.player) == before) { "Queue changed while resolving local tracks" }
                    val player = session.player
                    when (operation) {
                        "replace" -> {
                            if (resolved.isNotEmpty()) {
                                val start = resolved.indexOfFirst { it.mediaId == requested }.coerceAtLeast(0)
                                player.setMediaItems(resolved, start, 0L)
                                player.prepare(); player.play()
                            } else if (items.isEmpty()) player.clearMediaItems()
                        }
                        "append" -> player.addMediaItems(resolved)
                        "insertNext" -> {
                            player.addMediaItems((player.currentMediaItemIndex + 1)
                                .coerceIn(0, player.mediaItemCount), resolved)
                            placeNextInShuffle(player, resolved.map { it.mediaId })
                        }
                    }
                }
                "moveNext", "remove", "play" -> {
                    val player = session.player
                    val index = (0 until player.mediaItemCount).firstOrNull {
                        player.getMediaItemAt(it).mediaId == uid
                    }
                    if (index != null) when (operation) {
                        "remove" -> player.removeMediaItem(index)
                        "play" -> { player.seekToDefaultPosition(index); player.prepare(); player.play() }
                        "moveNext" -> {
                            val current = player.currentMediaItemIndex
                            if (index != current) {
                                player.moveMediaItem(index,
                                    (if (index < current) current else current + 1)
                                        .coerceIn(0, player.mediaItemCount - 1))
                                placeNextInShuffle(player, listOf(uid))
                            }
                        }
                    }
                }
                "clearUpcoming" -> {
                    val player = session.player
                    // In shuffle mode upcoming means traversal order, not physical suffix.
                    val timeline = player.currentTimeline
                    val upcoming = mutableSetOf<String>()
                    var index = player.currentMediaItemIndex
                    while (index >= 0 && index < player.mediaItemCount) {
                        index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
                        if (index == C.INDEX_UNSET) break
                        upcoming.add(player.getMediaItemAt(index).mediaId)
                    }
                    for (i in player.mediaItemCount - 1 downTo 0)
                        if (player.getMediaItemAt(i).mediaId in upcoming) player.removeMediaItem(i)
                }
                "clear" -> session.player.clearMediaItems()
                else -> return@withLock SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED)
            }
            SessionResult(SessionResult.RESULT_SUCCESS, Bundle().apply {
                if (missing) putString("error", "Some tracks are unavailable locally")
            })
        }
    }

    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>): ListenableFuture<List<MediaItem>> = future {
        mutations.withLock {
            val before = snapshot(session.player)
            val resolved = resolveMedia(mediaItems, before.toSet())
            reportMissing(session, resolved.size != mediaItems.size)
            // A native timeline edit while IO was pending must not resurrect old work.
            check(snapshot(session.player) == before) { "Queue changed while resolving local tracks" }
            resolved
        }
    }

    override fun onSetMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
        mutations.withLock {
            val before = snapshot(session.player)
            val input = normalize(mediaItems.map(QueueProtocol::fromMediaItem), emptySet())
            val requested = input.getOrNull(if (startIndex == C.INDEX_UNSET) 0 else startIndex)?.uid
            val resolved = input.mapNotNull { resolve(it)?.let { item ->
                QueueProtocol.toMediaItem(item, Uri.parse(requireNotNull(item.localUri)))
            } }
            check(snapshot(session.player) == before) { "Queue changed while resolving local tracks" }
            reportMissing(session, resolved.size != mediaItems.size)
            check(resolved.isNotEmpty() || mediaItems.isEmpty()) { "Tracks are unavailable locally" }
            val index = resolved.indexOfFirst { it.mediaId == requested }
            MediaSession.MediaItemsWithStartPosition(resolved, index.coerceAtLeast(0),
                if (index >= 0) startPositionMs else 0L)
        }
    }

    private suspend fun resolveMedia(items: List<MediaItem>, existing: Set<String>): List<MediaItem> =
        normalize(items.map(QueueProtocol::fromMediaItem), existing).mapNotNull { item ->
            resolve(item)?.let { QueueProtocol.toMediaItem(it, Uri.parse(requireNotNull(it.localUri))) }
        }

    private fun placeNextInShuffle(player: Player, uids: List<String>) {
        if (!player.shuffleModeEnabled || uids.isEmpty()) return
        val timeline = player.currentTimeline
        val order = mutableListOf<String>()
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET) {
            val uid = player.getMediaItemAt(index).mediaId
            if (uid !in uids) order.add(uid)
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        val current = player.currentMediaItem?.mediaId
        order.addAll((order.indexOf(current) + 1).coerceAtLeast(0), uids)
        val positions = (0 until player.mediaItemCount).associateBy { player.getMediaItemAt(it).mediaId }
        (player as androidx.media3.exoplayer.ExoPlayer).setShuffleOrder(
            androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(
                order.map { positions.getValue(it) }.toIntArray(), 0L
            )
        )
    }

    private fun snapshot(player: Player) = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }

    private fun reportMissing(session: MediaSession, missing: Boolean) {
        session.setSessionExtras(Bundle().apply {
            if (missing) putString("queue_error", "Some tracks are unavailable locally")
        })
    }

    private fun normalize(items: List<QueueItem>, existing: Set<String>): List<QueueItem> {
        val used = existing.toMutableSet()
        return items.map { item ->
            val valid = item.uid.length in 1..512 && item.uid.none { it.isISOControl() }
            val uid = if (valid && used.add(item.uid)) item.uid else {
                var generated: String
                do { generated = "${item.videoId.take(256)}|${UUID.randomUUID()}" } while (!used.add(generated))
                generated
            }
            item.copy(uid = uid)
        }
    }

    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = scope.launch {
            try { future.set(block()) }
            catch (cancelled: CancellationException) { future.cancel(false); throw cancelled }
            catch (_: Exception) { future.setException(IllegalStateException("Local queue operation failed")) }
        }
        job.invokeOnCompletion { if (!future.isDone) future.cancel(false) }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }
}
