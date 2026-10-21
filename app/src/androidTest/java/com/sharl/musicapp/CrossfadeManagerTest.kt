package com.sharl.musicapp

import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/**
 * Drives [CrossfadeManager] with two local 6-second WAV tracks and a 3-second
 * crossfade. Verifies the real volume ramp, the player handoff and that a
 * manual skip cancels the fade instead of leaving the old engine muted.
 */
@RunWith(AndroidJUnit4::class)
class CrossfadeManagerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val attrs = AudioAttributes.Builder()
        .setUsage(C.USAGE_MEDIA)
        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
        .build()

    private fun mediaItem(file: File, index: Int): MediaItem =
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
        header.putShort(1) // PCM
        header.putShort(1) // mono
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

    @Test
    fun crossfadeRampsBothPlayersAndHandsOffTheSession() = runBlocking(Dispatchers.Main) {
        val first = wav(6, "xfade-a.wav")
        val second = wav(6, "xfade-b.wav")
        val a = ExoPlayer.Builder(context).setAudioAttributes(attrs, false).build()
        val b = ExoPlayer.Builder(context).setAudioAttributes(attrs, false).build()
        val swapped = CompletableDeferred<Pair<ExoPlayer, ExoPlayer>>()
        val manager = CrossfadeManager(
            scope = this,
            playerA = a,
            playerB = b,
            crossfadeDurationMs = { 3_000L },
            onActivePlayerChanged = { newActive, old -> swapped.complete(newActive to old) },
            onTrackChanged = {},
            onPrefetchNext = {},
        )
        try {
            manager.start()
            a.setMediaItems(listOf(mediaItem(first, 0), mediaItem(second, 1)), 0, 0)
            a.prepare()
            a.play()

            // Fade starts 3 s before the end, so ~4.5 s in both engines should
            // be audible at a partial volume.
            val mid = async {
                delay(4_500)
                Triple(a.volume, b.volume, manager.isFading)
            }
            val (newActive, old) = withTimeout(20_000) { swapped.await() }
            val (volA, volB, fadingAtMid) = mid.await()

            assertTrue("expected both volumes ramped, got a=$volA b=$volB", volA in 0.15f..0.85f)
            assertTrue("standby should be audible mid-fade, got $volB", volB in 0.15f..0.85f)
            assertTrue("expected fade to be running at 4.5 s", fadingAtMid)
            assertSame("standby B must become the active player", b, newActive)
            assertSame(a, old)
            assertEquals(1f, b.volume, 0.001f)
            assertEquals(0f, a.volume, 0.001f)
            assertFalse(manager.isFading)
        } finally {
            manager.release()
            a.release()
            b.release()
            first.delete()
            second.delete()
        }
    }

    @Test
    fun manualSkipDuringFadeCancelsFadeAndKeepsActiveAudible() = runBlocking(Dispatchers.Main) {
        val first = wav(6, "xfade-c.wav")
        val second = wav(6, "xfade-d.wav")
        val a = ExoPlayer.Builder(context).setAudioAttributes(attrs, false).build()
        val b = ExoPlayer.Builder(context).setAudioAttributes(attrs, false).build()
        var swapped = false
        val manager = CrossfadeManager(
            scope = this,
            playerA = a,
            playerB = b,
            crossfadeDurationMs = { 3_000L },
            onActivePlayerChanged = { _, _ -> swapped = true },
            onTrackChanged = {},
            onPrefetchNext = {},
        )
        try {
            manager.start()
            a.setMediaItems(listOf(mediaItem(first, 0), mediaItem(second, 1)), 0, 0)
            a.prepare()
            a.play()

            withTimeout(15_000) {
                while (!manager.isFading) delay(100)
            }
            delay(700) // let the ramp move away from 1.0
            a.seekToNextMediaItem()
            delay(500)

            assertFalse("skip must cancel the fade", manager.isFading)
            assertEquals("active engine must be restored to full volume", 1f, a.volume, 0.001f)
            assertEquals("standby must be silenced", 0f, b.volume, 0.001f)
            assertTrue("active engine keeps playing the target", a.isPlaying)
            assertFalse("session must not hand off after a skip", swapped)
        } finally {
            manager.release()
            a.release()
            b.release()
            first.delete()
            second.delete()
        }
    }

    @Test
    fun pauseDuringFadeFreezesBothEnginesAndResumes() = runBlocking(Dispatchers.Main) {
        val first = wav(6, "xfade-e.wav")
        val second = wav(6, "xfade-f.wav")
        val a = ExoPlayer.Builder(context).setAudioAttributes(attrs, false).build()
        val b = ExoPlayer.Builder(context).setAudioAttributes(attrs, false).build()
        var swapped = false
        val manager = CrossfadeManager(
            scope = this,
            playerA = a,
            playerB = b,
            crossfadeDurationMs = { 3_000L },
            onActivePlayerChanged = { _, _ -> swapped = true },
            onTrackChanged = {},
            onPrefetchNext = {},
        )
        try {
            manager.start()
            a.setMediaItems(listOf(mediaItem(first, 0), mediaItem(second, 1)), 0, 0)
            a.prepare()
            a.play()
            withTimeout(15_000) {
                while (!manager.isFading) delay(100)
            }
            delay(400)
            a.pause()
            delay(300)
            assertFalse("active engine must pause", a.isPlaying)
            assertFalse("standby engine must pause too", b.isPlaying)
            val pausedA = a.volume
            val pausedB = b.volume
            delay(500)
            assertEquals("fade must not advance while paused", pausedA, a.volume, 0.02f)
            assertEquals("standby must hold its level", pausedB, b.volume, 0.02f)

            a.play()
            delay(600)
            assertTrue("standby must resume", b.isPlaying)
            assertTrue("ramp must continue", a.volume < pausedA - 0.02f)
            assertTrue("standby must keep rising", b.volume > pausedB + 0.02f)
            assertFalse(swapped)
        } finally {
            manager.release()
            a.release()
            b.release()
            first.delete()
            second.delete()
        }
    }
}
