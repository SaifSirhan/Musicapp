package com.sharl.musicapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.sharl.musicapp.data.SettingsRepo
import com.sharl.musicapp.data.download.RoomDownloadJobStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service owning the download queue drain. A yt-dlp transfer outlives any
 * single screen, so the drain must not live in a composition scope: this keeps one
 * persistent notification and survives screen lock and the Activity going away.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null
    private var reporter: Job? = null

    // Notification writes are throttled to percent changes that matter.
    private var shownVideoId: String? = null
    private var shownPercent = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Preparing downloads", 0, 0))
        shownVideoId = null
        shownPercent = -1
        reporter?.cancel()
        reporter = scope.launch { reportProgress() }
        work?.cancel()
        work = scope.launch {
            val finished = try {
                Downloader.queueDrainer(applicationContext, SettingsRepo(applicationContext)).drain()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            // stopSelf(startId) keeps the service alive when a newer enqueue already restarted it.
            stopForeground(STOP_FOREGROUND_REMOVE)
            if (!finished) postPaused()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        reporter?.cancel()
        work?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun reportProgress() {
        val store = RoomDownloadJobStore(applicationContext)
        Downloader.states.collect { states ->
            val active = states.entries.firstOrNull { it.value is DownloadState.Downloading } ?: return@collect
            val percent = (active.value as DownloadState.Downloading).percent
            if (active.key == shownVideoId && percent - shownPercent < 2) return@collect
            shownVideoId = active.key
            shownPercent = percent
            val remaining = store.resumable().count { it.videoId != active.key }
            val title = store.get(active.key)?.title?.takeIf { it.isNotBlank() } ?: "Audio"
            postNotification("Downloading $title", percent, percent + remaining)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress for music downloaded for offline listening"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, current: Int, total: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloads")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total.coerceAtLeast(1), current.coerceIn(0, total.coerceAtLeast(1)), false)
            .build()

    private fun postNotification(text: String, current: Int, total: Int) {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text, current, total))
        }
    }

    private fun postPaused() {
        runCatching {
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Downloads paused")
                .setContentText("Reopen the app to retry the remaining tracks")
                .setAutoCancel(true)
                .build().let {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, it)
                }
        }
    }

    companion object {
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 4301

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, DownloadService::class.java)
            )
        }
    }
}
