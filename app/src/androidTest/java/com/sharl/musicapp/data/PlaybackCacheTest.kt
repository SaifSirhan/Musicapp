package com.sharl.musicapp.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The downloader can publish a track before the service has ever been created. */
@RunWith(AndroidJUnit4::class)
class PlaybackCacheTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun entriesPublishedBeforeTheServiceInitializesTheCacheSurviveInitialization() {
        PlaybackCache.put("racechk00001", "file:///tmp/race.m4a", "Race", "Artist", null, null)
        PlaybackCache.init(context)
        assertEquals("Race", PlaybackCache.get("racechk00001")?.title)
        PlaybackCache.remove("racechk00001")
        assertNull(PlaybackCache.get("racechk00001"))
    }

    @Test
    fun entriesPublishedBeforeTheServiceInitializesTheCacheArePersisted() {
        PlaybackCache.put("savechk00001", "file:///tmp/save.m4a", "Saved", "Artist", null, null)
        PlaybackCache.init(context)
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        assertTrue(json.contains("savechk00001"))
        PlaybackCache.remove("savechk00001")
    }

    private companion object {
        const val PREFS = "playback_cache"
        const val KEY = "entries"
    }
}
