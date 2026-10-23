package com.sharl.musicapp.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongDaoDuplicateTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SongDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.songDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun song(
        id: String,
        artist: String = "Daft Punk",
        title: String = "One More Time (Official Audio)",
        normalized: String = "one more time",
        version: Int = 1,
        durationMs: Long = 320_000L
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        lastPlayedAt = 0L,
        playCount = 0,
        durationMs = durationMs,
        normalizedTitle = normalized,
        normalizedTitleVersion = version
    )

    @Test
    fun findNearDuplicateRequiresArtistTitleAndKnownDuration() = runBlocking {
        dao.upsert(song("a"))

        assertEquals("a", dao.findNearDuplicate("Daft Punk", "one more time", 321_000L, 2_500L)?.videoId)
        assertEquals("a", dao.findNearDuplicate("daft punk", "one more time", 320_000L, 2_500L)?.videoId)
        assertNull(dao.findNearDuplicate("Daft Punk", "one more time", 400_000L, 2_500L))
        assertNull(dao.findNearDuplicate("Ram", "one more time", 320_000L, 2_500L))
        assertNull(dao.findNearDuplicate("Daft Punk", "aerodynamic", 320_000L, 2_500L))
    }

    @Test
    fun unknownDurationsAreNeverDuplicates() = runBlocking {
        dao.upsert(song("a", durationMs = 0L))

        assertNull(dao.findNearDuplicate("Daft Punk", "one more time", 0L, 2_500L))
        assertNull(dao.findNearDuplicate("Daft Punk", "one more time", 320_000L, 2_500L))
    }

    @Test
    fun staleRowsAreNotUsedForDuplicateMatching() = runBlocking {
        dao.upsert(song("a", normalized = "", version = 0))

        assertNull(dao.findNearDuplicate("Daft Punk", "one more time", 320_000L, 2_500L))
        assertEquals(listOf("a"), dao.songsNeedingNormalization(1, 10).map { it.videoId })
        assertTrue(dao.findByNormalizedTitle("one more time", 10).isEmpty())
    }

    @Test
    fun backfillSelectsOnlyRowsBehindTheCurrentVersion() = runBlocking {
        dao.upsert(song("v0", version = 0))
        dao.upsert(song("v1", version = 1))
        dao.upsert(song("v2", version = 2))

        assertEquals(listOf("v0"), dao.songsNeedingNormalization(1, 10).map { it.videoId })
        assertTrue(dao.findByNormalizedTitle("one more time", 10).none { it.videoId == "v0" })

        dao.setNormalization("v0", "one more time", 1)
        assertTrue(dao.songsNeedingNormalization(1, 10).isEmpty())
        assertEquals(3, dao.findByNormalizedTitle("one more time", 10).size)
    }

    @Test
    fun backfillDurationsFromDownloadsOnlyFillsMissingValues() = runBlocking {
        dao.upsert(song("a", durationMs = 0L))
        dao.upsert(song("b", durationMs = 111_000L))

        db.downloadDao().upsert(
            Download(
                videoId = "a",
                filePath = "/tmp/a.m4a",
                fileSizeBytes = 1L,
                downloadedAt = 0L,
                title = "One More Time",
                artist = "Daft Punk",
                thumbnailUrl = null,
                durationMs = 320_000L
            )
        )

        assertEquals(1, dao.backfillDurationsFromDownloads())
        assertEquals(320_000L, dao.getById("a")?.durationMs)
        assertEquals(111_000L, dao.getById("b")?.durationMs)
    }
}
