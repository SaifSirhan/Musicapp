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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
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
 * End-to-end check of the crossfade inside the real [PlaybackService]: a
 * MediaController loads two local tracks, the service fades them, hands the
 * MediaSession to the standby player and keeps the controller playing.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackServiceCrossfadeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun mediaSessionHandsOffToStandbyAndKeepsPlaying() = runBlocking(Dispatchers.Main) {
        SettingsRepo(context).crossfadeSeconds = 3
        val first = wav(6, "svc-a.wav")
        val second = wav(6, "svc-b.wav")
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
            val nextReached = async {
                withTimeout(20_000) {
                    QueueAdvanceSignaler.trackChanged.first { it == 1 }
                }
            }
            controller.setMediaItems(
                listOf(item(first, 0), item(second, 1)),
                0,
                0
            )
            controller.prepare()
            controller.play()

            assertEquals(1, nextReached.await())
            assertEquals("track-1", controller.currentMediaItem?.mediaId)
            assertTrue("controller must still be playing after handoff", controller.isPlaying)
        } finally {
            runCatching { controller?.stop() }
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
