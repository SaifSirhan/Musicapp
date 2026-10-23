package com.sharl.musicapp.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LibraryReconcilerTest {

    private class FakeStore(
        val downloads: MutableMap<String, Download>,
        val songs: MutableMap<String, Song>
    ) : LibraryReconcileStore {
        val upserted = ArrayList<Download>()
        val deleted = ArrayList<String>()
        val durationWrites = LinkedHashMap<String, Long>()

        override suspend fun downloads(): List<Download> = downloads.values.toList()

        override suspend fun upsertDownload(download: Download) {
            upserted.add(download)
            downloads[download.videoId] = download
        }

        override suspend fun deleteDownload(videoId: String) {
            deleted.add(videoId)
            downloads.remove(videoId)
        }

        override suspend fun song(videoId: String): Song? = songs[videoId]

        override suspend fun setSongDuration(videoId: String, durationMs: Long) {
            durationWrites[videoId] = durationMs
            songs[videoId]?.let { songs[videoId] = it.copy(durationMs = durationMs) }
        }
    }

    private fun download(
        id: String,
        path: String = "/music/$id.m4a",
        size: Long = 1_000L,
        duration: Long = 200_000L
    ) = Download(
        videoId = id,
        filePath = path,
        fileSizeBytes = size,
        downloadedAt = 1L,
        title = id,
        artist = "Artist",
        thumbnailUrl = null,
        durationMs = duration
    )

    private fun song(id: String, duration: Long = 200_000L) = Song(
        videoId = id,
        title = id,
        artist = "Artist",
        lastPlayedAt = 0L,
        playCount = 0,
        durationMs = duration
    )

    @Test
    fun missingFileDropsOnlyTheDatabaseRow() = runBlocking {
        val store = FakeStore(
            mutableMapOf("gone" to download("gone")),
            mutableMapOf("gone" to song("gone"))
        )
        val reconciler = LibraryReconciler(
            store,
            readDuration = { null },
            fileLength = { null }
        )

        val report = reconciler.reconcile()

        assertEquals(1, report.removedDownloads)
        assertTrue(store.downloads.isEmpty())
        assertTrue("song metadata must survive", store.songs.containsKey("gone"))
    }

    @Test
    fun mismatchedFileSizeIsRepaired() = runBlocking {
        val store = FakeStore(mutableMapOf("a" to download("a", size = 900L)), mutableMapOf())
        val reconciler = LibraryReconciler(
            store,
            readDuration = { 200_000L },
            fileLength = { 1_024L }
        )

        val report = reconciler.reconcile()

        assertEquals(1, report.repairedSizes)
        assertEquals(1_024L, store.downloads.getValue("a").fileSizeBytes)
        assertTrue(store.deleted.isEmpty())
    }

    @Test
    fun durationMismatchUpdatesDownloadAndSong() = runBlocking {
        val store = FakeStore(
            mutableMapOf("a" to download("a", duration = 180_000L)),
            mutableMapOf("a" to song("a", duration = 180_000L))
        )
        val reconciler = LibraryReconciler(
            store,
            readDuration = { 205_000L },
            fileLength = { 1_000L }
        )

        val report = reconciler.reconcile()

        assertEquals(1, report.updatedDurations)
        assertEquals(205_000L, store.downloads.getValue("a").durationMs)
        assertEquals(205_000L, store.durationWrites["a"])
        assertEquals(205_000L, store.songs.getValue("a").durationMs)
    }

    @Test
    fun smallDurationDifferenceIsLeftAlone() = runBlocking {
        val store = FakeStore(
            mutableMapOf("a" to download("a", duration = 200_000L)),
            mutableMapOf("a" to song("a", duration = 200_000L))
        )
        val reconciler = LibraryReconciler(
            store,
            readDuration = { 201_000L },
            fileLength = { 1_000L }
        )

        val report = reconciler.reconcile()

        assertEquals(0, report.updatedDurations)
        assertTrue(store.upserted.isEmpty())
        assertTrue(store.durationWrites.isEmpty())
    }

    @Test
    fun unknownSongDurationIsFilledEvenWhenDownloadIsCorrect() = runBlocking {
        val store = FakeStore(
            mutableMapOf("a" to download("a", duration = 200_000L)),
            mutableMapOf("a" to song("a", duration = 0L))
        )
        val reconciler = LibraryReconciler(
            store,
            readDuration = { 200_000L },
            fileLength = { 1_000L }
        )

        val report = reconciler.reconcile()

        assertEquals(1, report.updatedDurations)
        assertEquals(200_000L, store.durationWrites["a"])
    }

    @Test
    fun unreadableDurationLeavesMetadataUntouched() = runBlocking {
        val store = FakeStore(
            mutableMapOf("a" to download("a", duration = 200_000L)),
            mutableMapOf("a" to song("a", duration = 200_000L))
        )
        val reconciler = LibraryReconciler(
            store,
            readDuration = { null },
            fileLength = { 1_000L }
        )

        val report = reconciler.reconcile()

        assertEquals(1, report.unreadableDurations)
        assertTrue(store.upserted.isEmpty())
        assertTrue(store.durationWrites.isEmpty())
    }

    @Test
    fun reconciliationIsIdempotent() = runBlocking {
        val store = FakeStore(
            mutableMapOf("a" to download("a", size = 900L, duration = 180_000L)),
            mutableMapOf("a" to song("a", duration = 180_000L))
        )
        val reconciler = LibraryReconciler(
            store,
            readDuration = { 205_000L },
            fileLength = { 1_000L }
        )

        reconciler.reconcile()
        val second = reconciler.reconcile()

        assertEquals(0, second.repairedSizes)
        assertEquals(0, second.updatedDurations)
        assertEquals(0, second.removedDownloads)
    }

    @Test
    fun presentAudioIsNeverRemoved() = runBlocking {
        val file = File.createTempFile("probe", ".m4a").apply {
            writeBytes(ByteArray(64))
            deleteOnExit()
        }
        val store = FakeStore(
            mutableMapOf("a" to download("a", path = file.absolutePath, size = 64L)),
            mutableMapOf("a" to song("a"))
        )
        val reconciler = LibraryReconciler(
            store,
            readDuration = { 200_000L },
            fileLength = { file.takeIf { it.exists() }?.length() }
        )

        reconciler.reconcile()

        assertTrue("existing audio must not be deleted", file.exists())
        assertTrue(store.deleted.isEmpty())
    }
}
