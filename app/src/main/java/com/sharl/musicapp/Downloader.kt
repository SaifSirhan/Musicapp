package com.sharl.musicapp

import android.content.Context
import android.util.Log
import com.sharl.musicapp.data.Download
import com.sharl.musicapp.data.DownloadRepository
import com.sharl.musicapp.data.SettingsRepo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

sealed class DownloadState {
    object Idle : DownloadState()
    object Queued : DownloadState()
    data class Downloading(val percent: Int) : DownloadState()
    data class Done(val file: File) : DownloadState()
    data class Failed(val msg: String) : DownloadState()
}

object Downloader {

    private val mutex = Mutex()

    @Volatile
    private var initialized = false

    private val _states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val states: StateFlow<Map<String, DownloadState>> = _states

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            YoutubeDL.getInstance().init(context.applicationContext)
            try {
                YoutubeDL.getInstance().updateYoutubeDL(context, UpdateChannel.NIGHTLY)
                Log.d("DOWNLOAD", "yt-dlp updated successfully")
            } catch (e: Exception) {
                Log.e("DOWNLOAD", "yt-dlp update failed", e)
            }
            initialized = true
        }
    }

    private fun repo(context: Context) = DownloadRepository(context.applicationContext)

    private fun setState(videoId: String, st: DownloadState?) {
        _states.update { current ->
            if (st == null) current - videoId else current + (videoId to st)
        }
    }

    fun stateOf(videoId: String): DownloadState = _states.value[videoId] ?: DownloadState.Idle

    // Phase 43: Simple download for search results (uses defaults for missing metadata)
    suspend fun downloadSimple(
        context: Context,
        settings: SettingsRepo,
        videoId: String,
        title: String,
        artist: String,
        thumb: String?
    ): File? = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        mutex.withLock {
            setState(videoId, DownloadState.Queued)
            try {
                ensureInitialized(appContext)
                val dir = settings.downloadDir(appContext)
                val outputTemplate = File(dir, "%(id)s.%(ext)s").absolutePath

                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                    addOption("--no-playlist")
                    addOption("--newline")
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                    addOption("-o", outputTemplate)
                    addOption("--print", "after_move:filepath")
                    addOption("--extractor-args",
                        "youtube:player_client=ios,tv,mweb,android_vr,web_embedded,tv_embedded")
                    addOption("--remote-components", "ejs:github")
                    addOption("--no-update")
                    addOption("--no-warnings")
                }

                val response = YoutubeDL.getInstance().execute(
                    request,
                    processId = null,
                    callback = { progress, _, _ ->
                        if (progress >= 0f) {
                            setState(videoId, DownloadState.Downloading(progress.toInt()))
                        }
                    }
                )

                val path = response.out.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .lastOrNull()

                val file = path?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                if (file == null) {
                    Log.e("DOWNLOAD", "downloadSimple produced no file for $videoId")
                    setState(videoId, DownloadState.Failed("No file created"))
                    return@withLock null
                }

                // Add to downloads database
                val download = Download(
                    videoId = videoId,
                    filePath = file.absolutePath,
                    fileSizeBytes = file.length(),
                    downloadedAt = System.currentTimeMillis(),
                    title = title,
                    artist = artist,
                    thumbnailUrl = thumb,
                    durationMs = 0L
                )
                repo(appContext).upsert(download)
                
                setState(videoId, DownloadState.Done(file))
                Log.d("DOWNLOAD", "downloadSimple success: $videoId -> ${file.absolutePath}")
                file
            } catch (e: Exception) {
                Log.e("DOWNLOAD", "downloadSimple failed for $videoId: ${e.message}", e)
                setState(videoId, DownloadState.Failed(e.message ?: "Unknown error"))
                null
            }
        }
    }

    // Phase 43: All downloads go to permanent library folder. No separate cache.
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
        mutex.withLock {
            setState(videoId, DownloadState.Queued)
            try {
                ensureInitialized(appContext)
                val dir = settings.downloadDir(appContext)
                val outputTemplate = File(dir, "%(id)s.%(ext)s").absolutePath

                val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                    addOption("--no-playlist")
                    addOption("--newline")
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                    addOption("-o", outputTemplate)
                    addOption("--print", "after_move:filepath")
                    addOption("--extractor-args",
                        "youtube:player_client=ios,tv,mweb,android_vr,web_embedded,tv_embedded")
                    addOption("--remote-components", "ejs:github")
                    addOption("--no-update")
                    addOption("--no-warnings")
                }

                val response = YoutubeDL.getInstance().execute(
                    request,
                    processId = null,
                    callback = { progress, _, _ ->
                        if (progress >= 0f) {
                            setState(videoId, DownloadState.Downloading(progress.toInt()))
                        }
                    }
                )

                val finalPath = response.out
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .lastOrNull()
                val file = finalPath?.let { File(it) }
                if (file == null || !file.exists()) {
                    setState(videoId, DownloadState.Failed("download produced no file"))
                    throw IllegalStateException("download produced no file")
                }

                val row = Download(
                    videoId = videoId,
                    filePath = file.absolutePath,
                    fileSizeBytes = file.length(),
                    downloadedAt = System.currentTimeMillis(),
                    title = title,
                    artist = artist,
                    thumbnailUrl = thumb,
                    durationMs = durationMs
                )
                repo(appContext).upsert(row)
                setState(videoId, DownloadState.Done(file))
                Log.d("DOWNLOAD", "downloaded $videoId -> ${file.absolutePath}")
                row
            } catch (e: Exception) {
                Log.e("DOWNLOAD", "download failed for $videoId: ${e.message}", e)
                setState(videoId, DownloadState.Failed(e.message ?: "unknown error"))
                throw e
            }
        }
    }

    suspend fun remove(context: Context, download: Download) = withContext(Dispatchers.IO) {
        try {
            File(download.filePath).delete()
        } catch (e: Exception) {
            Log.w("DOWNLOAD", "could not delete ${download.filePath}", e)
        }
        repo(context.applicationContext).delete(download.videoId)
        setState(download.videoId, null)
    }

    // Phase 43: All downloads go to permanent library folder. No separate cache.
}
