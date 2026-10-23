package com.sharl.musicapp.data.download

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Only an actual M4A stream is accepted; never disguise WebM with an m4a suffix. */
class YtdlpDownloadEngine(context: Context) : DownloadEngine {
    private val appContext = context.applicationContext

    override suspend fun download(
        request: DownloadRequest,
        targetDir: File,
        onProgress: (Int) -> Unit
    ): File {
        val caller = currentCoroutineContext()
        // Keep the coordinator lock until execute has joined its process, including cancellation.
        return withContext(Dispatchers.IO + NonCancellable) {
            require(DownloadCoordinator.validVideoId(request.videoId))
            YoutubeDL.getInstance().init(appContext)
            val temp = DownloadCoordinator.child(targetDir, "${request.videoId}.m4a.part")
            // yt-dlp owns the additional .part suffix until transport finishes. Never publish it.
            DownloadCoordinator.child(targetDir, "${request.videoId}.m4a.part.part")
            val ytRequest = YoutubeDLRequest("https://www.youtube.com/watch?v=${request.videoId}").apply {
                addOption("--no-playlist")
                addOption("--newline")
                addOption("--continue")
                addOption("--no-overwrites")
                addOption("-f", "bestaudio[ext=m4a]")
                addOption("-o", temp.absolutePath)
                addOption("--extractor-args", "youtube:player_client=ios,tv,mweb,android_vr,web_embedded,tv_embedded")
                addOption("--remote-components", "ejs:github")
                addOption("--no-update")
                addOption("--no-warnings")
            }
            val processId = UUID.randomUUID().toString()
            val lowSpace = AtomicBoolean(false)
            val monitor = launch {
                while (isActive) {
                    if (DownloadCoordinator.availableSpace(appContext, targetDir) < 200L * 1024 * 1024) {
                        lowSpace.set(true)
                    }
                    if (!caller.isActive || lowSpace.get()) YoutubeDL.getInstance().destroyProcessById(processId)
                    delay(1000)
                }
            }
            try {
                YoutubeDL.getInstance().execute(ytRequest, processId = processId,
                    callback = { progress, _, _ -> if (progress >= 0f) onProgress(progress.toInt()) })
                // Successful execute has waited for the writer. Persist before cancellation checks.
                // Exceptions/destroyProcessById alone do not prove native-child termination.
                DownloadCoordinator.markStopped(targetDir)
            } catch (e: Exception) {
                caller.ensureActive()
                if (lowSpace.get() || DownloadCoordinator.isOutOfSpace(e)) throw IOException("Insufficient storage")
                throw IOException("Download could not be completed")
            } finally {
                monitor.cancelAndJoin()
            }
            caller.ensureActive()
            if (lowSpace.get()) throw IOException("Insufficient storage")
            // Ignore stdout paths: the only admissible result is our expected in-root file.
            require(temp.isFile && temp.length() > 0L) { "Download produced no file" }
            temp
        }
    }
}
