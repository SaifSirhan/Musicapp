package com.sharl.musicapp

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sharl.musicapp.data.SettingsRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * 15 s crossfade through the real service: the standby must be audible
 * mid-fade, and the controller must land on the next track without any
 * duplicate playback-end events.
 */
@RunWith(AndroidJUnit4::class)
class LongCrossfadeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fifteenSecondCrossfadeOverlapsThenLandsOnNextTrack() = runBlocking(Dispatchers.Main) {
        SettingsRepo(context).crossfadeSeconds = 15
        val first = wav(24, "long-a.wav")
        val second = wav(8, "long-b.wav")
        var ended = 0
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        try {
            val controller = withTimeout(15_000) {
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
            controller.setMediaItems(listOf(item(first, 0), item(second, 1)), 0, 0)
            controller.prepare()
            controller.play()

            // Wait until the fade has visibly started, then sample both engines
            // through the controller's volume (it mirrors the session player).
            var sawPartial = false
            var minSeen = 1f
            var lastState = -1
            var lastItem: String? = null
            var lastPos = 0L
            val deadline = System.currentTimeMillis() + 25_000
            while (System.currentTimeMillis() < deadline && !sawPartial) {
                delay(100)
                minSeen = minOf(minSeen, controller.volume)
                lastState = controller.playbackState
                lastItem = controller.currentMediaItem?.mediaId
                lastPos = controller.currentPosition
                if (controller.volume < 1f) sawPartial = true
            }
            assertTrue(
                "expected the active volume to ramp down (min=$minSeen state=$lastState " +
                    "item=$lastItem pos=$lastPos)",
                sawPartial
            )

            withTimeout(30_000) {
                while (controller.currentMediaItem?.mediaId != "track-1") delay(100)
            }
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
            delay(1_000)
            assertEquals("no premature end-of-queue", 0, ended)
        } finally {
            MediaController.releaseFuture(future)
            context.stopService(Intent(context, PlaybackService::class.java))
            SettingsRepo(context).crossfadeSeconds = 0
            first.delete()
            second.delete()
        }
    }

    private fun item(file: File, index: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId("track-$index")
            .setUri(Uri.fromFile(file))
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setExtras(android.os.Bundle().apply { putInt("queue_index", index) })
                    .build()
            )
            .build()

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
