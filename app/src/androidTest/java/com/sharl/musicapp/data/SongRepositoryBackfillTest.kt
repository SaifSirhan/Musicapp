package com.sharl.musicapp.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sharl.musicapp.data.matching.TitleNormalizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SongRepositoryBackfillTest {

    @Test
    fun duplicateLookupIncludesThreeSecondBoundaryButNotUnknownOrLive() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "test-boundary-${System.nanoTime()}"
        val repo = SongRepository(context)
        val dao = AppDatabase.get(context).songDao()
        try {
            repo.saveDownloadedSong(id, "Unique $id", "Test Artist", null, null, durationMs = 180_000)
            assertEquals(id, repo.findNearDuplicate("Test Artist", "Unique $id (Lyrics)", 183_000)?.videoId)
            org.junit.Assert.assertNull(repo.findNearDuplicate("Test Artist", "Unique $id", 183_001))
            org.junit.Assert.assertNull(repo.findNearDuplicate("Test Artist", "Unique $id", 0))
            org.junit.Assert.assertNull(repo.findNearDuplicate("Test Artist", "Unique $id (Live)", 180_000))
        } finally {
            AppDatabase.get(context).openHelper.writableDatabase.execSQL(
                "DELETE FROM songs WHERE videoId = ?", arrayOf(id)
            )
        }
    }

    @Test
    fun backfillsLegacyRowsAndPullsDurationFromDownloads() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "test-backfill-${System.nanoTime()}"
        val db = AppDatabase.get(context)
        val dao = db.songDao()

        dao.upsert(
            Song(
                videoId = id,
                title = "One More Time (Official Audio)",
                artist = "Daft Punk",
                lastPlayedAt = 0L,
                playCount = 0,
                durationMs = 0L,
                normalizedTitle = "",
                normalizedTitleVersion = 0
            )
        )
        db.downloadDao().upsert(
            Download(
                videoId = id,
                filePath = "/tmp/$id.m4a",
                fileSizeBytes = 1L,
                downloadedAt = 0L,
                title = "One More Time",
                artist = "Daft Punk",
                thumbnailUrl = null,
                durationMs = 320_000L
            )
        )

        try {
            val repo = SongRepository(context)
            repo.backfillNormalizedTitles()

            val row = dao.getById(id)!!
            assertEquals("one more time", row.normalizedTitle)
            assertEquals(TitleNormalizer.NORMALIZATION_VERSION, row.normalizedTitleVersion)
            assertEquals(320_000L, row.durationMs)

            repo.backfillNormalizedTitles()
            assertEquals(row, dao.getById(id))
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE videoId = ?", arrayOf(id))
            db.downloadDao().delete(id)
        }
    }
}
