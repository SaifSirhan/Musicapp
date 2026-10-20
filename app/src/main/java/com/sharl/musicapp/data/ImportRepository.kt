package com.sharl.musicapp.data

import android.content.Context
import android.util.Log
import com.sharl.musicapp.Downloader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

sealed class ImportState {
    object Idle : ImportState()
    data class Resolving(val done: Int, val total: Int) : ImportState()
    data class Previewing(val preview: ImportPreview) : ImportState()
    data class Committing(val done: Int, val total: Int) : ImportState()
    data class DownloadingOffline(val done: Int, val total: Int) : ImportState()
    data class Done(val playlistId: Long, val imported: Int, val skipped: Int) : ImportState()
    data class Error(val message: String) : ImportState()
}

class ImportRepository(private val context: Context) {

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    private var currentJob: Job? = null

    // Repository-owned, not the caller's scope: an offline import keeps
    // downloading when the activity is backgrounded mid-import.
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun preview(source: ImportSource, job: Job? = null): ImportPreview {
        currentJob = job
        _state.value = ImportState.Resolving(0, 0)
        return try {
            val result = ImportResolver(context).resolve(source) { done, total ->
                _state.value = ImportState.Resolving(done, total)
            }
            _state.value = ImportState.Previewing(result)
            result
        } catch (e: CancellationException) {
            _state.value = ImportState.Idle
            throw e
        } catch (e: Exception) {
            _state.value = ImportState.Error(e.message ?: "Import failed.")
            ImportPreview(sourceName = "", tracks = emptyList())
        }
    }

    suspend fun commit(preview: ImportPreview, downloadForOffline: Boolean = false): Long =
        withContext(Dispatchers.IO) {
            val total = preview.tracks.size
            _state.value = ImportState.Committing(0, total)
            return@withContext try {
                val playlistRepo = PlaylistRepository(context)
                val songDao = AppDatabase.get(context).songDao()
                val playlistId = playlistRepo.create(preview.sourceName)
                var imported = 0
                for ((i, track) in preview.tracks.withIndex()) {
                    currentCoroutineContext().ensureActive()
                    val vid = track.videoId
                    if (vid != null) {
                        if (songDao.getById(vid) == null) {
                            songDao.upsert(
                                Song(
                                    videoId = vid,
                                    title = track.title,
                                    artist = track.artist,
                                    thumbnailUrl = track.thumbnailUrl,
                                    lastPlayedAt = 0L,
                                    playCount = 0,
                                    isFavorite = false,
                                    isHidden = false
                                )
                            )
                        }
                        playlistRepo.addSong(playlistId, vid, track.title, track.artist, track.thumbnailUrl)
                        imported++
                    }
                    _state.value = ImportState.Committing(i + 1, total)
                }
                if (downloadForOffline) {
                    startOfflineDownloads(
                        playlistId = playlistId,
                        imported = imported,
                        skipped = preview.unresolved.size,
                        tracks = preview.tracks
                    )
                } else {
                    _state.value = ImportState.Done(playlistId, imported, preview.unresolved.size)
                }
                playlistId
            } catch (e: CancellationException) {
                _state.value = ImportState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = ImportState.Error(e.message ?: "Import failed.")
                -1L
            }
        }

    // Runs the download pass in [downloadScope] so it outlives the import
    // screen. Downloader's own mutex serialises one yt-dlp process at a time.
    private fun startOfflineDownloads(
        playlistId: Long,
        imported: Int,
        skipped: Int,
        tracks: List<ImportTrack>
    ) {
        val pending = tracks.filter { it.videoId != null }
        if (pending.isEmpty()) {
            _state.value = ImportState.Done(playlistId, imported, skipped)
            return
        }
        _state.value = ImportState.DownloadingOffline(0, pending.size)
        downloadScope.launch {
            val downloads = DownloadRepository(context)
            val settings = SettingsRepo(context)
            var done = 0
            for (track in pending) {
                val vid = track.videoId ?: continue
                val alreadyLocal = downloads.get(vid)?.let { File(it.filePath).exists() } == true
                if (!alreadyLocal) {
                    try {
                        Downloader.download(
                            context, settings, vid, track.title, track.artist,
                            track.thumbnailUrl, 0L
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // One failure shouldn't abort the rest of the import.
                        Log.w("IMPORT", "offline download failed for $vid", e)
                    }
                }
                done++
                _state.value = ImportState.DownloadingOffline(done, pending.size)
            }
            // Leave Idle alone if the user already dismissed or opened the
            // playlist while downloads were still running.
            if (_state.value is ImportState.DownloadingOffline) {
                _state.value = ImportState.Done(playlistId, imported, skipped)
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        _state.value = ImportState.Idle
    }

    fun reset() {
        _state.value = ImportState.Idle
    }
}
