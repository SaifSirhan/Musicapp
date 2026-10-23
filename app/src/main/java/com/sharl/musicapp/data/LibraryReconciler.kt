package com.sharl.musicapp.data

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Startup repair for the local library. Downloads whose files are already gone are
 * dropped from the database, sizes are corrected, and durations are validated
 * against the audio. Completed audio files are never deleted here.
 */
class LibraryReconciler(
    private val store: LibraryReconcileStore,
    private val readDuration: (File) -> Long? = ::readAudioDuration,
    private val fileLength: (String) -> Long? = { path ->
        File(path).takeIf { it.isFile }?.length()
    },
    private val toleranceMs: Long = 2_000L
) {

    data class Report(
        val removedDownloads: Int = 0,
        val repairedSizes: Int = 0,
        val updatedDurations: Int = 0,
        val unreadableDurations: Int = 0
    )

    suspend fun reconcile(): Report {
        var removed = 0
        var repaired = 0
        var durations = 0
        var unreadable = 0

        for (download in store.downloads()) {
            val length = fileLength(download.filePath)
            if (length == null || length <= 0L) {
                // The audio is already gone; drop the stale row but never touch disk.
                store.deleteDownload(download.videoId)
                removed++
                continue
            }

            val current = if (length != download.fileSizeBytes) {
                repaired++
                download.copy(fileSizeBytes = length).also { store.upsertDownload(it) }
            } else {
                download
            }

            val actual = readDuration(File(download.filePath))
            if (actual == null || actual <= 0L) {
                unreadable++
                continue
            }
            val stored = current.durationMs
            if (stored <= 0L || kotlin.math.abs(stored - actual) > toleranceMs) {
                store.upsertDownload(current.copy(durationMs = actual))
                store.setSongDuration(download.videoId, actual)
                durations++
            } else {
                val song = store.song(download.videoId)
                if (song != null && (song.durationMs <= 0L ||
                        kotlin.math.abs(song.durationMs - actual) > toleranceMs)
                ) {
                    store.setSongDuration(download.videoId, actual)
                    durations++
                }
            }
        }

        return Report(removed, repaired, durations, unreadable)
    }

    companion object {
        fun forContext(context: Context): LibraryReconciler =
            LibraryReconciler(RoomLibraryReconcileStore(context.applicationContext))

        private fun readAudioDuration(file: File): Long? {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.takeIf { it > 0 }
            } catch (e: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}

/** Narrow persistence surface so reconciliation is testable without Room. */
interface LibraryReconcileStore {
    suspend fun downloads(): List<Download>
    suspend fun upsertDownload(download: Download)
    suspend fun deleteDownload(videoId: String)
    suspend fun song(videoId: String): Song?
    suspend fun setSongDuration(videoId: String, durationMs: Long)
}

class RoomLibraryReconcileStore(context: Context) : LibraryReconcileStore {
    private val downloadDao = AppDatabase.get(context).downloadDao()
    private val songDao = AppDatabase.get(context).songDao()

    override suspend fun downloads(): List<Download> = downloadDao.all().first()

    override suspend fun upsertDownload(download: Download) = downloadDao.upsert(download)

    override suspend fun deleteDownload(videoId: String) = downloadDao.delete(videoId)

    override suspend fun song(videoId: String): Song? = songDao.getById(videoId)

    override suspend fun setSongDuration(videoId: String, durationMs: Long) {
        if (songDao.getById(videoId) != null) songDao.setDuration(videoId, durationMs)
    }
}
