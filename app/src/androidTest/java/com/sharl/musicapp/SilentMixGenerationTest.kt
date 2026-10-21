package com.sharl.musicapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sharl.musicapp.data.MixRepository
import com.sharl.musicapp.data.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Live check of the silently generated station: the real generator must return
 * the seed first plus neighbours, which is what startMix swaps into the queue.
 */
@RunWith(AndroidJUnit4::class)
class SilentMixGenerationTest {

    @Test
    fun generatedStationStartsWithTheSeedAndHasNeighbours() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // M83 - Midnight City; already resolved earlier so InnerTube reachable.
        val seed = Song(
            videoId = "dX3k_QDnzHE",
            title = "Midnight City",
            artist = "M83",
            lastPlayedAt = 0L,
            playCount = 0
        )
        val mix = MixRepository(context).generateMix(seed)
        assertTrue("station should contain the seed", mix.first().videoId == seed.videoId)
        assertTrue("station should add neighbours, got ${mix.size}", mix.size > 5)
        assertTrue(
            "station must be deduplicated",
            mix.map { it.videoId }.distinct().size == mix.size
        )
    }
}
