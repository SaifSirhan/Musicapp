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

    // --- Playback cache ------------------------------------------------------
    // YouTube's modern progressive URLs reject ExoPlayer's headerless request
    // (HTTP 403) and only serve the first ~1 MB of the file. When that happens
    // the UI asks yt-dlp for the complete audio once, keeps it in cacheDir and
    // replays it locally. This is invisible to the Downloads library.

    private fun streamCacheDir(context: Context): File =
        File(context.cacheDir, "yt_stream").apply { mkdirs() }

    fun cachedStreamFile(context: Context, videoId: String): File? {
        if (videoId.isBlank()) return null
        return streamCacheDir(context.applicationContext).listFiles()?.firstOrNull {
            it.isFile && it.length() > 0L && !it.name.endsWith(".part") &&
                it.name.startsWith("$videoId.")
        }
    }

    suspend fun cacheForPlayback(context: Context, videoId: String): File? =
        withContext(Dispatchers.IO) {
            val appContext = context.applicationContext
            cachedStreamFile(appContext, videoId)?.let { return@withContext it }
            mutex.withLock {
                cachedStreamFile(appContext, videoId)?.let { return@withLock it }
                try {
                    ensureInitialized(appContext)
                    val dir = streamCacheDir(appContext)
                    val request = YoutubeDLRequest(
                        "https://www.youtube.com/watch?v=$videoId"
                    ).apply {
                        addOption("--no-playlist")
                        addOption("--newline")
                        addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
                        addOption("-o", File(dir, "%(id)s.%(ext)s").absolutePath)
                        addOption("--print", "after_move:filepath")
                        addOption("--extractor-args",
                            "youtube:player_client=ios,tv,mweb,android_vr,web_embedded,tv_embedded")
                        addOption("--remote-components", "ejs:github")
                        addOption("--no-update")
                        addOption("--no-warnings")
                    }
                    val response = YoutubeDL.getInstance().execute(request)
                    val path = response.out.lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .lastOrNull()
                    val file = path?.let { File(it) }?.takeIf { it.exists() && it.length() > 0L }
                    if (file == null) {
                        Log.e("DOWNLOAD", "playback cache produced no file for $videoId")
                        return@withLock null
                    }
                    pruneStreamCache(dir)
                    Log.d("DOWNLOAD", "cached for playback $videoId -> ${file.absolutePath}")
                    file
                } catch (e: Exception) {
                    Log.e("DOWNLOAD", "playback cache failed for $videoId: ${e.message}", e)
                    null
                }
            }
        }

    private fun pruneStreamCache(dir: File) {
        val files = dir.listFiles()?.filter { it.isFile } ?: return
        var total = files.sumOf { it.length() }
        var count = files.size
        val maxBytes = 600L * 1024 * 1024
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= maxBytes && count <= 40) break
            total -= f.length()
            if (f.delete()) count--
        }
    }
}
