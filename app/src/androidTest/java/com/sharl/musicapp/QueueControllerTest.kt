package com.sharl.musicapp

import android.net.Uri
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class QueueControllerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun item(uid: String, id: String = "abcdefghijk") = QueueItem(id, uid, uid = uid)
    private suspend fun eventually(predicate: () -> Boolean) {
        withTimeout(5_000) { while (!predicate()) delay(20) }
    }
    private suspend fun fixture(
        resolver: suspend (QueueItem) -> QueueItem? = { it.copy(localUri = "file:///unused.wav") },
        body: suspend (QueueController, ExoPlayer, MediaController) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val player = ExoPlayer.Builder(context).build()
        val session = MediaSession.Builder(context, player)
            // The service keeps an unnamed session alive in this process; media3 requires
            // a unique id per package, so each fixture registers its own.
            .setId("queue-test-" + java.util.UUID.randomUUID())
            .setCallback(LocalMediaSessionCallback(scope, resolver)).build()
        val controller = withContext(Dispatchers.IO) {
            MediaController.Builder(context, session.token).buildAsync().get()
        }
        val queue = QueueController(controller)
        try { body(queue, player, controller) }
        finally { queue.close(); controller.release(); session.release(); player.release(); scope.cancel() }
    }

    @Test fun backgroundCommandsAreAppliedOnControllerLooper() = runBlocking(Dispatchers.Main) {
        fixture { queue, _, _ ->
            withContext(Dispatchers.IO) { queue.append(listOf(item("background"))) }
            eventually { queue.queue.value.singleOrNull()?.uid == "background" }
            withContext(Dispatchers.IO) { queue.remove("background") }
            eventually { queue.queue.value.isEmpty() }
        }
    }

    @Test fun duplicateOccurrencesRemoveByUidAndClearUpcoming() = runBlocking(Dispatchers.Main) {
        fixture { queue, player, _ ->
            queue.append(listOf(item("a"), item("b"), item("c"), item("d")))
            eventually { queue.queue.value.size == 4 }
            assertEquals(1, queue.queue.value.map { it.videoId }.distinct().size)
            player.seekToDefaultPosition(1)
            eventually { queue.currentUid.value == "b" }
            queue.remove("a")
            eventually { queue.queue.value.map { it.uid } == listOf("b", "c", "d") }
            assertEquals("b", queue.currentUid.value)
            queue.clearUpcoming()
            eventually { queue.queue.value.map { it.uid } == listOf("b") }
            queue.remove("b")
            eventually { queue.queue.value.isEmpty() && queue.currentUid.value == null }
        }
    }

    @Test fun removeCurrentMovesToNextAndMoveNextPreservesOccurrence() = runBlocking(Dispatchers.Main) {
        fixture { queue, player, _ ->
            queue.append(listOf(item("a"), item("b"), item("c"), item("d")))
            eventually { queue.queue.value.size == 4 }
            player.seekToDefaultPosition(1)
            queue.moveNext("a")
            eventually { queue.queue.value.map { it.uid } == listOf("b", "a", "c", "d") }
            assertEquals("b", queue.currentUid.value)
            queue.remove("b")
            eventually { queue.currentUid.value == "a" }
            queue.clear()
            eventually { queue.queue.value.isEmpty() }
        }
    }

    @Test fun collidingAndMissingUidsAreRegenerated() = runBlocking(Dispatchers.Main) {
        fixture { queue, _, _ ->
            queue.append(listOf(item("same"), item("same"), item("")))
            eventually { queue.queue.value.size == 3 }
            val ids = queue.queue.value.map { it.uid }
            assertEquals("same", ids.first())
            assertEquals(3, ids.toSet().size)
            assertTrue(ids.none { it.isBlank() })
        }
    }

    @Test fun asyncMissingItemsKeepRequestedStartOccurrence() = runBlocking(Dispatchers.Main) {
        fixture(resolver = { delay(30); if (it.uid == "missing") null else it.copy(localUri = "file:///unused.wav") }) { queue, _, _ ->
            queue.replace(listOf(item("missing"), item("a"), item("b")), "b")
            eventually { queue.queue.value.size == 2 }
            assertEquals("b", queue.currentUid.value)
            eventually { queue.errors.value != null }
            assertEquals("Some tracks are unavailable locally", queue.errors.value)
        }
    }

    @Test fun insertUsesCurrentOccurrenceAfterLookupAndQueuedRemovalWins() = runBlocking(Dispatchers.Main) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(resolver = {
            if (it.uid == "pending") { entered.complete(Unit); release.await() }
            it.copy(localUri = "file:///unused.wav")
        }) { queue, player, _ ->
            queue.append(listOf(item("a"), item("b"), item("c")))
            eventually { queue.queue.value.size == 3 }
            queue.insertNext(item("pending"))
            withTimeout(5_000) { entered.await() }
            player.seekToDefaultPosition(1)
            queue.remove("pending")
            release.complete(Unit)
            eventually { queue.queue.value.none { it.uid == "pending" } && queue.currentUid.value == "b" }
            // A following command is a barrier behind the pending insert and remove.
            queue.append(listOf(item("barrier")))
            eventually { queue.queue.value.lastOrNull()?.uid == "barrier" }
            assertEquals(listOf("a", "b", "c", "barrier"), queue.queue.value.map { it.uid })
        }
    }

    @Test fun insertUsesFreshIndexAfterNativeTransition() = runBlocking(Dispatchers.Main) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(resolver = {
            if (it.uid == "pending") { entered.complete(Unit); release.await() }
            it.copy(localUri = "file:///unused.wav")
        }) { queue, player, _ ->
            queue.append(listOf(item("a"), item("b"), item("c")))
            eventually { queue.queue.value.size == 3 }
            queue.insertNext(item("pending"))
            withTimeout(5_000) { entered.await() }
            player.seekToDefaultPosition(1)
            release.complete(Unit)
            eventually { queue.queue.value.size == 4 }
            assertEquals(listOf("a", "b", "pending", "c"), queue.queue.value.map { it.uid })
        }
    }

    @Test fun nativeClearDuringLookupDoesNotResurrectQueue() = runBlocking(Dispatchers.Main) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        fixture(resolver = {
            if (it.uid == "pending") { entered.complete(Unit); release.await() }
            it.copy(localUri = "file:///unused.wav")
        }) { queue, player, _ ->
            queue.append(listOf(item("a")))
            eventually { queue.queue.value.size == 1 }
            queue.insertNext(item("pending"))
            withTimeout(5_000) { entered.await() }
            player.clearMediaItems()
            release.complete(Unit)
            eventually { queue.errors.value != null }
            assertTrue(queue.queue.value.isEmpty())
        }
    }

    @Test fun playNextAndMoveNextOverrideShuffleSuccessorWithoutDisablingShuffle() = runBlocking(Dispatchers.Main) {
        fixture { queue, player, _ ->
            queue.append(listOf(item("a"), item("b"), item("c"), item("d")))
            eventually { queue.queue.value.size == 4 }
            player.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(intArrayOf(1, 3, 0, 2), 0L))
            player.shuffleModeEnabled = true
            player.seekToDefaultPosition(1)
            queue.moveNext("c")
            eventually { player.getMediaItemAt(player.nextMediaItemIndex).mediaId == "c" }
            assertTrue(player.shuffleModeEnabled)
            assertEquals("c", player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
            queue.insertNext(item("pending"))
            eventually { queue.queue.value.size == 5 }
            assertTrue(player.shuffleModeEnabled)
            assertEquals("pending", player.getMediaItemAt(player.nextMediaItemIndex).mediaId)
        }
    }

    @Test fun nativeVideoIdsResolveLocallyWithDistinctOccurrenceUids() = runBlocking(Dispatchers.Main) {
        val id = "nativeid123"
        val file = File(context.cacheDir, "native-queue-test.wav")
        try {
            withContext(Dispatchers.IO) {
                file.writeBytes(byteArrayOf(1, 2, 3))
                com.sharl.musicapp.data.PlaybackCache.put(id, Uri.fromFile(file).toString(), "Native track", "Artist", null, null)
            }
            fixture(resolver = LocalPlaybackResolver(context)::resolve) { queue, _, controller ->
                val media = androidx.media3.common.MediaItem.Builder().setMediaId(id).build()
                controller.addMediaItems(listOf(media, media,
                    androidx.media3.common.MediaItem.Builder().setMediaId("missing1234").build()))
                eventually { queue.queue.value.size == 2 }
                assertTrue(queue.queue.value.all { it.videoId == id && it.uid.startsWith("$id|") })
                assertEquals(2, queue.queue.value.map { it.uid }.toSet().size)
            }
        } finally {
            withContext(Dispatchers.IO) {
                file.delete()
                com.sharl.musicapp.data.PlaybackCache.remove(id)
            }
        }
    }

    @Test fun nativeSetSkipsMissingBeforeRequestedStart() = runBlocking(Dispatchers.Main) {
        fixture(resolver = { if (it.uid == "missing") null else it.copy(localUri = "file:///unused.wav") }) { queue, _, controller ->
            val items = listOf(item("missing"), item("a"), item("b")).map {
                QueueProtocol.toMediaItem(it, Uri.EMPTY)
            }
            controller.setMediaItems(items, 2, 123L)
            eventually { queue.queue.value.size == 2 }
            assertEquals("b", queue.currentUid.value)
        }
    }

    @Test fun realResolverRejectsNetworkExternalPathsAndMissingFiles() = runBlocking {
        val resolver = LocalPlaybackResolver(context)
        assertNull(resolver.resolve(item("a").copy(localUri = "https://example.org/a")))
        assertNull(resolver.resolve(item("a").copy(localUri = "file:///sdcard/a.mp3")))
        assertNull(resolver.resolve(item("a").copy(videoId = "../invalid")))
        val file = File(context.cacheDir, "queue-resolver-test.wav")
        try {
            file.writeBytes(byteArrayOf(1, 2, 3))
            com.sharl.musicapp.data.PlaybackCache.put("abcdefghijk", Uri.fromFile(file).toString(), "local", "", null, null)
            assertNotNull(resolver.resolve(item("a")))
            file.delete()
            assertNull(resolver.resolve(item("a")))
        } finally {
            file.delete()
            com.sharl.musicapp.data.PlaybackCache.remove("abcdefghijk")
        }
    }
}
