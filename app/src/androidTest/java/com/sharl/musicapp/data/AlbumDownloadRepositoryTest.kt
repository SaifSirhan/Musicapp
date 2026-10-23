package com.sharl.musicapp.data

import androidx.test.platform.app.InstrumentationRegistry
import com.sharl.musicapp.data.download.DownloadRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class AlbumDownloadRepositoryTest {
    @Test
    fun concurrentLyricVariantsReuseOneCompletedFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = UUID.randomUUID().toString()
        val ids = listOf("album-a-$tag", "album-b-$tag")
        val title = "Test song $tag"
        val db = AppDatabase.get(context)
        val file = File(context.cacheDir, "album-test-$tag.m4a")
        var transfers = 0
        val repository = AlbumDownloadRepository(context) { request ->
            transfers++
            file.writeBytes(byteArrayOf(1, 2, 3))
            Download(request.videoId, file.absolutePath, 3, 0, request.title,
                request.artist, null, request.durationMs).also { db.downloadDao().upsert(it) }
        }
        try {
            val rows = ids.mapIndexed { index, id ->
                async(Dispatchers.IO) {
                    repository.downloadTrack(
                        DownloadRequest(id, if (index == 0) title else "$title (Lyrics)", "Test Artist", durationMs = 180_000),
                        "Test Album", index + 1
                    )
                }
            }.awaitAll()
            assertEquals(1, transfers)
            assertEquals(rows[0].videoId, rows[1].videoId)
            assertTrue(file.isFile)
            assertEquals(180_000L, db.songDao().getById(rows[0].videoId)?.durationMs)
        } finally {
            ids.forEach { id ->
                db.openHelper.writableDatabase.execSQL("DELETE FROM download_jobs WHERE videoId = ?", arrayOf(id))
                db.openHelper.writableDatabase.execSQL("DELETE FROM downloads WHERE videoId = ?", arrayOf(id))
                db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE videoId = ?", arrayOf(id))
            }
            file.delete()
        }
    }

    @Test
    fun missingExistingFileDoesNotSuppressDownload() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = UUID.randomUUID().toString()
        val oldId = "album-old-$tag"
        val newId = "album-new-$tag"
        val title = "Missing file song $tag"
        val db = AppDatabase.get(context)
        val file = File(context.cacheDir, "missing-album-test-$tag.m4a")
        SongRepository(context).saveDownloadedSong(oldId, title, "Test Artist", null, null, durationMs = 180_000)
        db.downloadDao().upsert(Download(oldId, file.absolutePath, 3, 0, title, "Test Artist", null, 180_000))
        var transfers = 0
        val repository = AlbumDownloadRepository(context) { request ->
            transfers++
            file.writeBytes(byteArrayOf(1))
            Download(request.videoId, file.absolutePath, 1, 0, title, request.artist, null, request.durationMs)
        }
        try {
            val row = repository.downloadTrack(DownloadRequest(newId, title, "Test Artist", durationMs = 180_000), null, null)
            assertEquals(newId, row.videoId)
            assertEquals(1, transfers)
        } finally {
            listOf(oldId, newId).forEach { id ->
                db.openHelper.writableDatabase.execSQL("DELETE FROM download_jobs WHERE videoId = ?", arrayOf(id))
                db.openHelper.writableDatabase.execSQL("DELETE FROM downloads WHERE videoId = ?", arrayOf(id))
                db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE videoId = ?", arrayOf(id))
            }
            file.delete()
        }
    }

    @Test
    fun queuedRowCarriesAlbumMetadataForAWorkerOutsideTheUi() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tag = UUID.randomUUID().toString()
        val id = "meta-$tag"
        val db = AppDatabase.get(context)
        val file = File(context.cacheDir, "meta-test-$tag.m4a")
        var albumInRow: String? = null
        var trackInRow: Int? = null
        val repository = AlbumDownloadRepository(context) { request ->
            db.downloadJobDao().get(request.videoId)?.let {
                albumInRow = it.album
                trackInRow = it.trackNumber
            }
            file.writeBytes(byteArrayOf(1))
            Download(request.videoId, file.absolutePath, 1, 0, request.title, request.artist, null, request.durationMs)
        }
        try {
            repository.downloadTrack(
                DownloadRequest(id, "Meta song $tag", "Meta Artist", durationMs = 120_000), "Meta Album", 7
            )
            assertEquals("Meta Album", albumInRow)
            assertEquals(7, trackInRow)
        } finally {
            db.openHelper.writableDatabase.execSQL("DELETE FROM download_jobs WHERE videoId = ?", arrayOf(id))
            db.openHelper.writableDatabase.execSQL("DELETE FROM downloads WHERE videoId = ?", arrayOf(id))
            db.openHelper.writableDatabase.execSQL("DELETE FROM songs WHERE videoId = ?", arrayOf(id))
            file.delete()
        }
    }
}
