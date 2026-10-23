package com.sharl.musicapp

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sharl.musicapp.data.PlaybackCache
import com.sharl.musicapp.data.SettingsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * 15 s crossfade through the real service, driven by [QueueController]: the
 * active engine must ramp down mid-fade, the session must hand off to the next
 * occurrence while keeping the seed in history, and no premature end-of-queue
 * may be reported.
 */
@RunWith(AndroidJUnit4::class)
class LongCrossfadeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fifteenSecondCrossfadeOverlapsThenLandsOnNextTrack() = runBlocking(Dispatchers.Main) {
        SettingsRepo(context).crossfadeSeconds = 15
        val first = wav(24, "long-a.wav")
        val second = wav(8, "long-b.wav")
        val files = listOf(first, second)
        val ids = listOf("longxfade01", "longxfade02")
        ids.forEachIndexed { index, id ->
            PlaybackCache.put(id, Uri.fromFile(files[index]).toString(), "Long $index", "", null, null)
        }
        var ended = 0
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        var controller: MediaController? = null
        try {
            controller = withTimeout(15_000) {
                val latch = kotlinx.coroutines.CompletableDeferred<MediaController>()
                future.addListener(
                    { runCatching { future.get() }
                        .onSuccess { latch.complete(it) }
                        .onFailure { latch.completeExceptionally(it) } },
                    ContextCompat.getMainExecutor(context)
                )
                latch.await()
            }
            controller.addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == androidx.media3.common.Player.STATE_ENDED) ended++
                }
            })
            val queue = QueueController(controller)
            val items = ids.mapIndexed { index, id -> QueueItem(id, "Long $index", uid = "$id|long-$index") }
            val landed = async {
                withTimeout(45_000) { queue.currentUid.first { it == items[1].uid } }
            }
            queue.replace(items, items[0].uid)
            withTimeout(15_000) {
                while (controller.mediaItemCount == 0) delay(50)
            }
            assertEquals(
                "both occurrences must resolve locally",
                2, controller.mediaItemCount
            )

            // Sample the session volume while the fade is running: the standby must
            // be audible, so the active engine has to ramp below full volume.
            var sawPartial = false
            var minSeen = 1f
            var lastState = -1
            var lastItem: String? = null
            var lastPos = 0L
            val deadline = System.currentTimeMillis() + 30_000
            while (System.currentTimeMillis() < deadline && !sawPartial && !landed.isCompleted) {
                delay(100)
                minSeen = minOf(minSeen, controller.volume)
                lastState = controller.playbackState
                lastItem = controller.currentMediaItem?.mediaId
                lastPos = controller.currentPosition
                if (controller.volume < 1f) sawPartial = true
            }
            assertTrue(
                "expected the active volume to ramp down (min=$minSeen state=$lastState " +
                    "item=$lastItem pos=$lastPos queueError=${queue.errors.value} " +
                    "playing=${controller.isPlaying} playWhenReady=${controller.playWhenReady})",
                sawPartial
            )

            assertEquals(items[1].uid, landed.await())

            // The fade started while the queue was still resolving, so by the time
            // the handoff is reported the session player must be back at full volume.
            assertEquals(1f, controller.volume, 0.001f)
            // The second track is delivered without playWhenReady, and that is
            // deliberate: media3 pauses after every delivered command, so the
            // target track would otherwise start twice (and skip a track).
            // The session must report PLAYING with playWhenReady set.
            assertEquals(
                "expected PLAYING after handoff",
                android.media.session.PlaybackState.STATE_PLAYING,
                controller.playbackState
            )
            assertTrue(controller.playWhenReady)
            assertEquals(items[1].uid, controller.currentMediaItem?.mediaId)
            assertEquals("handoff keeps the seed in history", 2, controller.mediaItemCount)
            assertEquals(items[0].uid, controller.getMediaItemAt(0).mediaId)
            assertEquals(items[1].uid, queue.queue.value.lastOrNull()?.uid)
            delay(1_000)
            assertEquals("no premature end-of-queue", 0, ended)
        } finally {
            runCatching { controller?.stop() }
            MediaController.releaseFuture(future)
            context.stopService(Intent(context, PlaybackService::class.java))
            SettingsRepo(context).crossfadeSeconds = 0
            ids.forEach { PlaybackCache.remove(it) }
            first.delete()
            second.delete()
        }
    }

    private fun wav(seconds: Int, name: String): File {
        val sampleRate = 44_100
        val samples = sampleRate * seconds
        val dataSize = samples * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray())
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray())
        header.put("fmt ".toByteArray())
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray())
        header.putInt(dataSize)
        val pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until samples) {
            val value = (sin(2 * PI * 440.0 * i / sampleRate) * 8000).toInt()
            pcm.putShort(value.toShort())
        }
        return File(context.cacheDir, name).apply {
            outputStream().use { out ->
                out.write(header.array())
                out.write(pcm.array())
            }
        }
    }
}
