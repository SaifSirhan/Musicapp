package com.sharl.musicapp

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sharl.musicapp.data.PlaybackCache
import kotlinx.coroutines.CompletableDeferred
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
 * Mirrors the silent-mix sequence through [QueueController]: play the seed as a
 * one-item queue, then append the generated station while the seed is playing.
 * The seed must keep its position, keep playing, and stay the current item.
 */
@RunWith(AndroidJUnit4::class)
class SilentMixSwapTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun swappingTheStationInDoesNotRestartTheSeed() = runBlocking(Dispatchers.Main) {
        val seed = wav(30, "mix-seed.wav")
        val station = listOf(wav(20, "mix-1.wav"), wav(20, "mix-2.wav"))
        val seedId = "mixseed0001"
        val stationIds = listOf("mixstat0001", "mixstat0002")
        PlaybackCache.put(seedId, Uri.fromFile(seed).toString(), "Seed", "", null, null)
        stationIds.forEachIndexed { index, id ->
            PlaybackCache.put(id, Uri.fromFile(station[index]).toString(), "Station $index", "", null, null)
        }
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
            val queue = QueueController(controller)
            try {
                val seedItem = QueueItem(seedId, "Seed", uid = "$seedId|seed")
                queue.replace(listOf(seedItem), seedItem.uid)
                // Let the seed get into a playing position.
                withTimeout(10_000) {
                    while (controller.playbackState != Player.STATE_READY || !controller.isPlaying) {
                        delay(50)
                    }
                }
                delay(1_500)
                val positionBefore = controller.currentPosition
                val itemBefore = controller.currentMediaItemIndex

                // Silent-mix swap: append the station behind the still-playing seed.
                val stationItems = stationIds.mapIndexed { index, id ->
                    QueueItem(id, "Station $index", uid = "$id|station-$index")
                }
                queue.append(stationItems)
                withTimeout(10_000) {
                    while (queue.queue.value.size < 3) delay(50)
                }
                // Give the seed real playback time so the position delta below
                // measures continuation, not just how fast the append resolved.
                delay(1_200)

                val positionAfter = controller.currentPosition
                assertTrue(
                    "seed must keep playing (was $positionBefore, now $positionAfter)",
                    positionAfter >= positionBefore + 500
                )
                assertEquals("still on the seed item", itemBefore, controller.currentMediaItemIndex)
                assertEquals("station appended", 3, controller.mediaItemCount)
                assertEquals(seedItem.uid, controller.currentMediaItem?.mediaId)
                assertEquals(
                    "queue reflects the timeline",
                    (listOf(seedItem) + stationItems).map { it.uid },
                    queue.queue.value.map { it.uid }
                )
                assertTrue(controller.isPlaying)
            } finally { queue.close() }
        } finally {
            runCatching { controller?.stop() }
            MediaController.releaseFuture(future)
            context.stopService(Intent(context, PlaybackService::class.java))
            (listOf(seedId) + stationIds).forEach { PlaybackCache.remove(it) }
            seed.delete()
            station.forEach { it.delete() }
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
