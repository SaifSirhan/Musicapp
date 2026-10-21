package com.sharl.musicapp

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
 * Mirrors the Phase 26 silent-mix sequence: play the seed as a one-item queue,
 * then swap the generated station into the tail of the loaded window while the
 * seed is playing. The seed must keep its position and keep playing.
 */
@RunWith(AndroidJUnit4::class)
class SilentMixSwapTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun swappingTheStationInDoesNotRestartTheSeed() = runBlocking(Dispatchers.Main) {
        val seed = wav(30, "mix-seed.wav")
        val station = listOf(wav(20, "mix-1.wav"), wav(20, "mix-2.wav"))
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        val connected = CompletableDeferred<MediaController>()
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { connected.complete(it) }
                    .onFailure { connected.completeExceptionally(it) }
            },
            ContextCompat.getMainExecutor(context)
        )
        var controller: MediaController? = null
        try {
            controller = withTimeout(15_000) { connected.await() }
            controller.setMediaItems(listOf(item(seed, 0)), 0, 0)
            controller.prepare()
            controller.play()
            // Let the seed get into a playing position.
            withTimeout(10_000) {
                while (controller.playbackState != Player.STATE_READY || !controller.isPlaying) {
                    delay(50)
                }
            }
            delay(1_500)
            val positionBefore = controller.currentPosition
            val itemBefore = controller.currentMediaItemIndex

            // Silent-mix swap: replace only the tail of the loaded window.
            val loadedIndex = controller.currentMediaItemIndex
            if (controller.mediaItemCount > loadedIndex + 1) {
                controller.removeMediaItems(loadedIndex + 1, controller.mediaItemCount)
            }
            controller.addMediaItems(station.mapIndexed { i, f -> item(f, i + 1) })
            delay(1_200)

            val positionAfter = controller.currentPosition
            assertTrue(
                "seed must keep playing (was $positionBefore, now $positionAfter)",
                positionAfter >= positionBefore + 500
            )
            assertEquals("still on the seed item", itemBefore, controller.currentMediaItemIndex)
            assertEquals("station appended", 3, controller.mediaItemCount)
            assertTrue(controller.isPlaying)
        } finally {
            runCatching { controller?.stop() }
            MediaController.releaseFuture(future)
            context.stopService(Intent(context, PlaybackService::class.java))
            seed.delete()
            station.forEach { it.delete() }
        }
    }

    private fun item(file: File, index: Int): MediaItem =
        MediaItem.Builder()
            .setMediaId("mix-$index")
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
