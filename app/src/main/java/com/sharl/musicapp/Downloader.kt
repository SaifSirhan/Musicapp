package com.sharl.musicapp

import android.content.Context
import com.sharl.musicapp.data.Download
import com.sharl.musicapp.data.DownloadJob
import com.sharl.musicapp.data.DownloadRepository
import com.sharl.musicapp.data.SettingsRepo
import com.sharl.musicapp.data.download.DownloadCoordinator
import com.sharl.musicapp.data.download.DownloadQueueDrainer
import com.sharl.musicapp.data.download.DownloadRequest
import com.sharl.musicapp.data.download.DownloadResult
import com.sharl.musicapp.data.download.DrainVerdict
import com.sharl.musicapp.data.download.RoomDownloadJobStore
import com.sharl.musicapp.data.download.YtdlpDownloadEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed class DownloadState {
    object Idle : DownloadState()
    object Queued : DownloadState()
    data class Downloading(val percent: Int) : DownloadState()
    data class Done(val file: File) : DownloadState()
    data class WaitingForSpace(val availableBytes: Long, val requiredBytes: Long) : DownloadState()
    data class Failed(val msg: String) : DownloadState()
}

object Downloader {

    private val mutex = Mutex()

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    private fun repo(context: Context) = DownloadRepository(context.applicationContext)

    private fun setState(videoId: String, st: DownloadState?) {
        _states.update { current ->
            if (st == null) current - videoId else current + (videoId to st)
        }
    }

    fun stateOf(videoId: String): DownloadState = _states.value[videoId] ?: DownloadState.Idle

    private fun audioRoot(context: Context, settings: SettingsRepo): File {
        val base = if (settings.storageExternal) context.getExternalFilesDir(null) else context.filesDir
        return DownloadCoordinator.child(base ?: throw IOException("Download storage unavailable"), "downloads")
            .apply { mkdirs() }
    }

    private fun authorizedRoots(context: Context): List<File> =
        listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
            .map { DownloadCoordinator.child(it, "downloads") }

    private fun allRootsAvailable(context: Context): Boolean =
        context.getExternalFilesDir(null)?.let {
            android.os.Environment.getExternalStorageState(it) == android.os.Environment.MEDIA_MOUNTED
        } == true

    /** Persist acceptance before any in-process transfer/album lock. Existing metadata is immutable. */
    suspend fun enqueue(
        context: Context,
        settings: SettingsRepo,
        request: DownloadRequest,
        album: String? = null,
        trackNumber: Int? = null
    ): Unit = withContext(Dispatchers.IO) {
        coordinator(context.applicationContext, settings).enqueue(request, album, trackNumber)
    }

    private fun coordinator(context: Context, settings: SettingsRepo): DownloadCoordinator =
        DownloadCoordinator(
            store = RoomDownloadJobStore(context.applicationContext),
            engine = YtdlpDownloadEngine(context.applicationContext),
            targetDir = { audioRoot(context.applicationContext, settings) },
            availableBytes = { DownloadCoordinator.availableSpace(context.applicationContext, it) },
            lockDir = { DownloadCoordinator.child(context.applicationContext.filesDir, "downloads") },
            authorizedRoots = { authorizedRoots(context.applicationContext) },
            rootsAvailable = { allRootsAvailable(context.applicationContext) }
        )

    private fun progress(videoId: String): (Int) -> Unit = { percent ->
        setState(videoId, DownloadState.Downloading(percent))
    }

    private suspend fun linkToLibrary(
        context: Context,
        store: RoomDownloadJobStore,
        videoId: String,
        file: File,
        title: String,
        artist: String,
        thumb: String?,
        durationMs: Long
    ): Download {
        val accepted = store.get(videoId)
        val row = Download(
            videoId = videoId,
            filePath = file.absolutePath,
            fileSizeBytes = file.length(),
            downloadedAt = System.currentTimeMillis(),
            title = accepted?.title ?: title,
            artist = accepted?.artist ?: artist,
            thumbnailUrl = if (accepted != null) accepted.thumbnailUrl else thumb,
            durationMs = accepted?.durationMs ?: durationMs
        )
        repo(context).upsert(row)
        com.sharl.musicapp.data.SongRepository(context).saveDownloadedSong(
            row.videoId, row.title, row.artist, accepted?.album, row.thumbnailUrl,
            trackNumber = accepted?.trackNumber, durationMs = row.durationMs
        )
        store.delete(videoId)
        return row
    }

    private suspend fun handleResult(
        context: Context,
        store: RoomDownloadJobStore,
        videoId: String,
        title: String,
        artist: String,
        thumb: String?,
        durationMs: Long,
        result: DownloadResult
    ): Download? {
        return when (result) {
            DownloadResult.Busy -> {
                setState(videoId, DownloadState.Queued)
                null
            }
            is DownloadResult.Published -> {
                val row = linkToLibrary(
                    context, store, videoId, result.file, title, artist, thumb, durationMs
                )
                setState(videoId, DownloadState.Done(result.file))
                row
            }
            is DownloadResult.AlreadyPresent -> {
                val row = linkToLibrary(
                    context, store, videoId, result.file, title, artist, thumb, durationMs
                )
                setState(videoId, DownloadState.Done(result.file))
                row
            }
            is DownloadResult.WaitingForSpace -> {
                setState(
                    videoId,
                    DownloadState.WaitingForSpace(result.availableBytes, result.requiredBytes)
                )
                null
            }
            is DownloadResult.Failed -> {
                setState(videoId, DownloadState.Failed(result.message))
                null
            }
        }
    }

    // Phase 50: durable, storage-aware download with atomic publication.
    suspend fun download(
        context: Context,
        settings: SettingsRepo,
        videoId: String,
        title: String,
        artist: String,
        thumb: String?,
        durationMs: Long
    ): Download = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val request = DownloadRequest(videoId, title, artist, thumb, durationMs)
        enqueue(appContext, settings, request)
        mutex.withLock {
            setState(videoId, DownloadState.Queued)
            val store = RoomDownloadJobStore(appContext)
            val result = try {
                coordinator(appContext, settings).run(request, progress(videoId))
            } catch (e: CancellationException) {
                setState(videoId, DownloadState.Queued)
                throw e
            } catch (e: Exception) {
                setState(videoId, DownloadState.Failed("Download could not be completed"))
                throw IOException("Download could not be completed")
            }
            if (result is DownloadResult.WaitingForSpace) {
                setState(
                    videoId,
                    DownloadState.WaitingForSpace(result.availableBytes, result.requiredBytes)
                )
                throw IOException("Not enough storage to download $videoId")
            }
            handleResult(appContext, store, videoId, title, artist, thumb, durationMs, result)
                ?: throw IllegalStateException("Download failed for $videoId")
        }
    }

    /** Re-links downloads published just before process death. Idempotent. */
    suspend fun recoverCompleted(context: Context, settings: SettingsRepo) =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            val store = RoomDownloadJobStore(appContext)
            val roots = authorizedRoots(appContext)
            for (job in store.completed()) {
                if (!DownloadCoordinator.validVideoId(job.videoId)) continue
                val candidates = roots.filter { job.destinationDir == null || it.path == job.destinationDir }
                val file = candidates.mapNotNull { dir ->
                    try { DownloadCoordinator.child(dir, "${job.videoId}.m4a") }
                    catch (_: IllegalArgumentException) { null }
                }.firstOrNull { it.isFile && it.length() > 0L }
                if (file != null) {
                    linkToLibrary(
                        appContext, store, job.videoId, file,
                        job.title, job.artist, job.thumbnailUrl, job.durationMs
                    )
                } else {
                    // Missing/unmounted external storage is not evidence that audio was lost.
                    if (candidates.isEmpty() || !allRootsAvailable(appContext)) continue
                    store.setStatus(
                        job.videoId,
                        DownloadJob.Status.FAILED,
                        job.attempts,
                        System.currentTimeMillis(),
                        "Published file missing"
                    )
                }
            }
        }

    /** Explicit queue drain, owned by a worker that outlives any single screen. */
    fun queueDrainer(context: Context, settings: SettingsRepo): DownloadQueueDrainer {
        val appContext = context.applicationContext
        val albumDownloads = com.sharl.musicapp.data.AlbumDownloadRepository(appContext)
        return DownloadQueueDrainer(
            store = RoomDownloadJobStore(appContext),
            recover = { coordinator(appContext, settings).recoverInterrupted() },
            transfer = { job ->
                val request = DownloadRequest(
                    job.videoId, job.title, job.artist, job.thumbnailUrl, job.durationMs
                )
                try {
                    albumDownloads.downloadTrack(request, job.album, job.trackNumber)
                    DrainVerdict.PROCEEDED
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Low storage blocks every remaining row; a failed row never blocks the queue.
                    if (stateOf(job.videoId) is DownloadState.WaitingForSpace) DrainVerdict.BLOCKED
                    else DrainVerdict.PROCEEDED
                }
            }
        )
    }

    suspend fun remove(context: Context, download: Download) = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(DownloadCoordinator.validVideoId(download.videoId)) { "Invalid download identifier" }
            val roots = listOfNotNull(context.filesDir, context.getExternalFilesDir(null))
                .map { DownloadCoordinator.child(it, "downloads") }
            val file = File(download.filePath).canonicalFile
            require(roots.any { root ->
                file == DownloadCoordinator.child(root, "${download.videoId}.m4a")
            }) { "File is outside the audio directory" }
            if (file.exists() && !file.delete()) throw IOException("Could not remove download")
            repo(context.applicationContext).delete(download.videoId)
            com.sharl.musicapp.data.PlaybackCache.remove(download.videoId)
            setState(download.videoId, null)
        }
    }

    // Phase 43: All downloads go to permanent library folder. No separate cache.
}
