package com.sharl.musicapp.data

import android.content.Context
import com.sharl.musicapp.Downloader
import com.sharl.musicapp.data.album.AlbumCandidate
import com.sharl.musicapp.data.album.AlbumTrackMetadata
import com.sharl.musicapp.data.download.DownloadRequest
import com.sharl.musicapp.data.matching.TitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class AlbumDownloadRepository(
    context: Context,
    private val transfer: suspend (DownloadRequest) -> Download = { request ->
        Downloader.download(context.applicationContext, SettingsRepo(context.applicationContext),
            request.videoId, request.title, request.artist, request.thumbnailUrl, request.durationMs)
    }
) {
    private val appContext = context.applicationContext
    private val songs = SongRepository(appContext)
    private val downloads = DownloadRepository(appContext)
    private val jobs = AppDatabase.get(appContext).downloadJobDao()

    suspend fun downloadTrack(request: DownloadRequest, album: String?, trackNumber: Int?): Download =
        withContext(Dispatchers.IO) {
            Downloader.enqueue(appContext, SettingsRepo(appContext), request)
            // Different video IDs can represent one recording; serialize the lookup with its save.
            albumLock.withLock {
                songs.backfillNormalizedTitles()
                downloads.get(request.videoId)?.takeIf(::isPresent)?.let { existing ->
                    songs.saveDownloadedSong(existing.videoId, request.title, request.artist, album,
                        request.thumbnailUrl, trackNumber, existing.durationMs)
                    jobs.discardQueued(request.videoId)
                    return@withLock existing
                }
                val key = TitleNormalizer.normalizedCore(request.title, request.artist)
                if (key.isNotBlank() && request.durationMs > 0 && request.artist.isNotBlank()) {
                    val target = AlbumCandidate(request.videoId, request.title, request.artist, request.durationMs)
                    for (song in songs.getDao().findByNormalizedTitle(key, Int.MAX_VALUE)) {
                        val candidate = AlbumCandidate(song.videoId, song.title, song.artist, song.durationMs)
                        if (!AlbumTrackMetadata.sameRecording(candidate, target)) continue
                        downloads.get(song.videoId)?.takeIf(::isPresent)?.let {
                            jobs.discardQueued(request.videoId)
                            return@withLock it
                        }
                    }
                }
                val row = transfer(request)
                songs.saveDownloadedSong(row.videoId, request.title, request.artist, album,
                    request.thumbnailUrl, trackNumber, row.durationMs)
                row
            }
        }

    private fun isPresent(download: Download): Boolean = File(download.filePath).let { it.isFile && it.length() > 0 }

    private companion object {
        val albumLock = Mutex()
    }
}
