package com.sharl.musicapp

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sharl.musicapp.data.ImportPreview
import com.sharl.musicapp.data.ImportRepository
import com.sharl.musicapp.data.ImportResolver
import com.sharl.musicapp.data.ImportTrack
import com.sharl.musicapp.importer.ImportManager
import com.sharl.musicapp.importer.ImportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Foreground service for account imports (Spotify / YouTube Music). The
 * playlist metadata is fetched by the UI; the service resolves unknown tracks
 * through yt-dlp (1s spacing inside ImportResolver) and writes them to Room, so
 * the work survives screen locks and app switching.
 */
class ImportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var work: Job? = null

    // Set by ACTION_CANCEL. Guards late progress/notification writes while the
    // blocking yt-dlp call in the cancelled coroutine finishes.
    @Volatile
    private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Phase 23: cancellation arrives either from the notification action or
        // the Import screen. Drop the running job and reset shared state.
        if (intent?.action == ACTION_CANCEL) {
            Log.d("IMPORT", "cancel requested")
            cancelled = true
            ImportManager.reset()
            work?.cancel()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        cancelled = false
        ImportManager.begin()
        val payload = intent?.getStringExtra(EXTRA_JOBS)
        if (intent?.action != ACTION_START || payload.isNullOrBlank()) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification("Preparing import…", 0, 0))
        work?.cancel()
        work = scope.launch {
            try {
                runJobs(JSONArray(payload))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportManager.update(ImportState.Error(e.message ?: "Import failed."))
                postNotification("Import failed", 0, 0, ongoing = false)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        work?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runJobs(jobs: JSONArray) {
        val parsed = (0 until jobs.length()).mapNotNull {
            runCatching { jobs.getJSONObject(it) }.getOrNull()
        }
        val total = parsed.sumOf { job ->
            job.optJSONArray("queries")?.length() ?: job.optJSONArray("tracks")?.length() ?: 0
        }
        var base = 0
        var imported = 0
        var skipped = 0
        ImportManager.update(ImportState.Running(0, total))
        postNotification("Preparing import…", 0, total)

        for (job in parsed) {
            if (cancelled) return
            val name = job.optString("name", "Imported").ifBlank { "Imported" }
            val queries = job.optJSONArray("queries")
            val tracksJson = job.optJSONArray("tracks")
            val count = queries?.length() ?: tracksJson?.length() ?: 0
            if (count == 0) continue

            val preview = when {
                queries != null -> {
                    val tracks = (0 until queries.length()).mapNotNull { i ->
                        val line = queries.optString(i, "").trim()
                        if (line.isEmpty()) null else splitArtistTitle(line)
                    }
                    ImportResolver(this).resolveQueries(name, tracks) { done, _ ->
                        val absolute = base + done
                        ImportManager.update(ImportState.Running(absolute, total))
                        postNotification("Resolving track $absolute of $total…", absolute, total)
                    }
                }
                else -> {
                    val tracks = (0 until (tracksJson?.length() ?: 0)).mapNotNull { i ->
                        val o = tracksJson?.optJSONObject(i) ?: return@mapNotNull null
                        val videoId = o.optString("videoId", "")
                        if (videoId.isBlank()) return@mapNotNull null
                        ImportTrack(
                            title = o.optString("title", ""),
                            artist = o.optString("artist", ""),
                            videoId = videoId,
                            thumbnailUrl = o.optString("thumbnailUrl", "").ifBlank { null }
                        )
                    }
                    ImportPreview(sourceName = name, tracks = tracks)
                }
            }

            if (cancelled) return
            ImportRepository(this).commit(preview, downloadForOffline = false)
            imported += preview.tracks.size
            skipped += preview.unresolved.size
            base += count
            ImportManager.update(ImportState.Running(base, total))
            postNotification("Imported ${base.coerceAtMost(total)} of $total…", base, total)
        }

        ImportManager.update(ImportState.Done(imported, skipped))
        postNotification(
            "Import complete — $imported songs added",
            total,
            total,
            ongoing = false
        )
    }

    private fun splitArtistTitle(line: String): ImportTrack {
        for (sep in listOf(" - ", " – ", " — ")) {
            val idx = line.indexOf(sep)
            if (idx > 0) {
                return ImportTrack(
                    title = line.substring(idx + sep.length).trim(),
                    artist = line.substring(0, idx).trim()
                )
            }
        }
        return ImportTrack(title = line, artist = "")
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Library Import",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progress for Spotify and YouTube Music imports"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, current: Int, total: Int) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Library Import")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(
                total.coerceAtLeast(0),
                current.coerceIn(0, total.coerceAtLeast(0)),
                false
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Cancel Import",
                cancelPendingIntent()
            )
            .build()

    private fun cancelPendingIntent(): PendingIntent {
        val intent = Intent(this, ImportService::class.java).setAction(ACTION_CANCEL)
        return PendingIntent.getService(
            this,
            CANCEL_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun postNotification(
        text: String,
        current: Int,
        total: Int,
        ongoing: Boolean = true
    ) {
        if (cancelled) return
        if (ongoing) {
            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, buildNotification(text, current, total))
            }
        } else {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Library Import")
                .setContentText(text)
                .setAutoCancel(true)
                .build()
            runCatching {
                NotificationManagerCompat.from(this).notify(NOTIFICATION_ID + 1, notification)
            }
        }
    }

    companion object {
        const val ACTION_START = "com.sharl.musicapp.action.START_ACCOUNT_IMPORT"
        const val ACTION_CANCEL = "com.sharl.musicapp.action.CANCEL_ACCOUNT_IMPORT"
        const val EXTRA_JOBS = "jobs"
        private const val CHANNEL_ID = "library_import"
        private const val NOTIFICATION_ID = 4201
        private const val CANCEL_REQUEST_CODE = 4202

        fun start(context: Context, jobs: JSONArray) {
            val intent = Intent(context, ImportService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_JOBS, jobs.toString())
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
        }

        // Delivered to the already-running service; stopSelf + onDestroy drop
        // the coroutine scope. ImportManager is reset immediately in
        // onStartCommand so the UI leaves the Running state at once.
        fun cancel(context: Context) {
            val intent = Intent(context, ImportService::class.java)
                .setAction(ACTION_CANCEL)
            runCatching { context.startService(intent) }
                .onFailure { Log.e("IMPORT", "cancel dispatch failed: ${it.message}") }
        }
    }
}
