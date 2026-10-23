package com.sharl.musicapp.data.download

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import com.sharl.musicapp.data.AppDatabase
import com.sharl.musicapp.data.DownloadJob
import com.sharl.musicapp.data.DownloadJobDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.io.IOException

data class DownloadRequest(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationMs: Long = 0L
)

sealed class DownloadResult {
    data class Published(val file: File) : DownloadResult()
    data class AlreadyPresent(val file: File) : DownloadResult()
    data class WaitingForSpace(val availableBytes: Long, val requiredBytes: Long) : DownloadResult()
    data class Failed(val message: String, val outOfSpace: Boolean = false) : DownloadResult()
    object Busy : DownloadResult()
}

interface DownloadEngine {
    suspend fun download(request: DownloadRequest, targetDir: File, onProgress: (Int) -> Unit): File
}

interface DownloadJobStore {
    suspend fun get(videoId: String): DownloadJob?
    suspend fun save(job: DownloadJob)
    suspend fun bindDestination(videoId: String, destinationDir: String)
    suspend fun claim(job: DownloadJob, now: Long): DownloadJob?
    suspend fun finish(videoId: String, status: String, attempts: Int, updatedAt: Long, lastError: String?): Int
    suspend fun setStatus(videoId: String, status: String, attempts: Int, updatedAt: Long, lastError: String?)
    suspend fun resumable(): List<DownloadJob>
    suspend fun completed(): List<DownloadJob>
    suspend fun requeueRunning(updatedAt: Long): Int
    suspend fun delete(videoId: String)
}

class RoomDownloadJobStore(context: Context) : DownloadJobStore {
    private val dao: DownloadJobDao = AppDatabase.get(context).downloadJobDao()
    override suspend fun get(videoId: String) = dao.get(videoId)
    override suspend fun save(job: DownloadJob) = dao.upsert(job)
    override suspend fun bindDestination(videoId: String, destinationDir: String) = dao.bindDestination(videoId, destinationDir)
    override suspend fun claim(job: DownloadJob, now: Long) = dao.claim(job, now)
    override suspend fun finish(videoId: String, status: String, attempts: Int, updatedAt: Long, lastError: String?) =
        dao.finish(videoId, status, attempts, updatedAt, lastError)
    override suspend fun setStatus(videoId: String, status: String, attempts: Int, updatedAt: Long, lastError: String?) =
        dao.setStatus(videoId, status, attempts, updatedAt, lastError)
    override suspend fun resumable() = dao.resumable()
    override suspend fun completed() = dao.completed()
    override suspend fun requeueRunning(updatedAt: Long) = dao.requeueRunning(updatedAt)
    override suspend fun delete(videoId: String) = dao.delete(videoId)
}

/** The OS lock covers all downloads and recovery, across coordinator instances and processes.
 * Never unlink its inode. No age-based lease expiry can steal a slow, still-live download.
 * Room's transactional claim and attempt CAS fence durable status transitions.
 */
class DownloadCoordinator(
    private val store: DownloadJobStore,
    private val engine: DownloadEngine,
    private val targetDir: () -> File,
    private val availableBytes: (File) -> Long = ::availableSpace,
    private val minFreeBytes: Long = 200L * 1024 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val lockDir: () -> File = targetDir,
    private val authorizedRoots: () -> List<File> = { listOf(targetDir()) },
    private val rootsAvailable: () -> Boolean = { true }
) {
    suspend fun finalFile(request: DownloadRequest): File {
        require(validVideoId(request.videoId)) { "Invalid download identifier" }
        val job = store.get(request.videoId)
        return child(resolveDestination(job) ?: throw IOException("Download storage unavailable"), "${request.videoId}.m4a")
    }

    private fun resolveDestination(job: DownloadJob?): File? {
        val roots = authorizedRoots().map { it.canonicalFile }
        if (job?.destinationDir != null) {
            return roots.firstOrNull { it.path == job.destinationDir }
        }
        // Pre-v17 jobs may have published on either volume, regardless of current settings.
        if (job != null) {
            roots.firstOrNull { child(it, "${job.videoId}.m4a").let { f -> f.isFile && f.length() > 0 } }
                ?.let { return it }
            if (!rootsAvailable()) return null
        }
        return targetDir().canonicalFile.takeIf { it in roots }
    }

    suspend fun enqueue(
        request: DownloadRequest,
        album: String? = null,
        trackNumber: Int? = null
    ) {
        require(validVideoId(request.videoId)) { "Invalid download identifier" }
        if (store.get(request.videoId) != null) return
        val dir = resolveDestination(null) ?: throw IOException("Download storage unavailable")
        val now = clock()
        store.save(DownloadJob(request.videoId, request.title, request.artist, request.thumbnailUrl,
            request.durationMs, DownloadJob.Status.QUEUED, 0, now, now, destinationDir = dir.path,
            album = album, trackNumber = trackNumber))
    }

    suspend fun run(request: DownloadRequest, onProgress: (Int) -> Unit = {}): DownloadResult {
        if (!validVideoId(request.videoId)) return DownloadResult.Failed("Invalid download identifier")
        enqueue(request)
        return withQueueLock {
            store.requeueRunning(clock())
            val previous = store.get(request.videoId) ?: return@withQueueLock DownloadResult.Busy
            val dir = resolveDestination(previous)
                ?: return@withQueueLock DownloadResult.WaitingForSpace(-1, minFreeBytes)
            store.bindDestination(request.videoId, dir.path)
            val seed = store.get(request.videoId) ?: return@withQueueLock DownloadResult.Busy
            dir.mkdirs()
            cleanupStoppedAttempts(dir)
            val final = child(dir, "${request.videoId}.m4a")
            if (previous?.status == DownloadJob.Status.COMPLETED && final.isFile && final.length() > 0) {
                return@withQueueLock DownloadResult.AlreadyPresent(final)
            }
            val job = store.claim(seed, clock()) ?: return@withQueueLock DownloadResult.Busy
            suspend fun finish(status: String, error: String? = null) {
                store.finish(job.videoId, status, job.attempts, clock(), error)
            }
            try {
                if (final.isFile && final.length() > 0) {
                    finish(DownloadJob.Status.COMPLETED)
                    return@withQueueLock DownloadResult.AlreadyPresent(final)
                }
                val free = availableBytes(dir)
                if (free < minFreeBytes) {
                    finish(DownloadJob.Status.WAITING_FOR_SPACE, "Not enough available storage")
                    return@withQueueLock DownloadResult.WaitingForSpace(free, minFreeBytes)
                }
                onProgress(0)
                // A native child could outlive a crashed app process. Fence its output as well
                // as its Room status: a subsequent attempt never reuses its working directory.
                val staging = child(dir, ".download-${request.videoId}-${java.util.UUID.randomUUID()}-${job.attempts}").apply { mkdirs() }
                val accepted = DownloadRequest(job.videoId, job.title, job.artist, job.thumbnailUrl, job.durationMs)
                val temp = engine.download(accepted, staging) { onProgress(it.coerceIn(0, 100)) }
                markStopped(staging)
                val expected = child(staging, "${request.videoId}.m4a.part")
                require(temp.canonicalFile == expected && temp.isFile && temp.length() > 0) {
                    "Invalid download output"
                }
                // No copy fallback: readers must never see a partially copied final file.
                // The queue lock excludes other publishers. Existing completed audio is immutable.
                if (!final.exists()) {
                    // Android File.renameTo delegates to the atomic same-filesystem rename(2).
                    // Staging is a child of the audio root, so no cross-volume copy is possible.
                    if (!temp.renameTo(final)) throw IOException("Could not publish download")
                } else require(final.isFile && final.length() > 0)
                finish(DownloadJob.Status.COMPLETED)
                DownloadResult.Published(final)
            } catch (e: CancellationException) {
                withContext(NonCancellable) { finish(DownloadJob.Status.QUEUED) }
                throw e
            } catch (e: Exception) {
                val outOfSpace = isOutOfSpace(e)
                val message = if (outOfSpace) "Not enough available storage" else "Download could not be completed"
                finish(if (outOfSpace) DownloadJob.Status.WAITING_FOR_SPACE else DownloadJob.Status.FAILED, message)
                // Unmarked attempts may still have native writers; never reclaim them by age.
                if (outOfSpace) DownloadResult.WaitingForSpace(availableBytes(dir), minFreeBytes)
                else DownloadResult.Failed(message)
            } finally {
                cleanupStoppedAttempts(dir)
            }
        } ?: DownloadResult.Busy
    }

    suspend fun recoverInterrupted(): Int {
        return withQueueLock {
            authorizedRoots().forEach { cleanupStoppedAttempts(it) }
            store.requeueRunning(clock())
        } ?: 0
    }

    private suspend fun <T> withQueueLock(action: suspend () -> T): T? {
        // POSIX may release a process's locks when ANY descriptor to that inode closes.
        // Do not open competing channels within this process, even merely to tryLock.
        if (!processLock.tryLock()) return null
        try {
            val lockRoot = lockDir().canonicalFile.apply { mkdirs() }
            RandomAccessFile(child(lockRoot, ".download-queue.lock"), "rw").use { file ->
                val lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (lock == null) return null
                try { return action() } finally { lock.release() }
            }
        } finally { processLock.unlock() }
    }

    companion object {
        private val processLock = Mutex()

        /** Only call after the writer has returned successfully, never merely after destroy(). */
        internal fun markStopped(staging: File) {
            java.io.FileOutputStream(child(staging, ".writer-stopped")).use { it.fd.sync() }
        }

        internal fun cleanupStoppedAttempts(root: File) {
            root.listFiles()?.forEach { candidate ->
                try {
                    if (!candidate.name.matches(Regex("\\.download-[A-Za-z0-9_-]+-[0-9]+"))) return@forEach
                    val dir = child(root, candidate.name)
                    if (!dir.isDirectory) return@forEach
                    val marker = child(dir, ".writer-stopped")
                    if (!marker.isFile) return@forEach
                    // No recursion or symlink following. Unknown nested output is retained.
                    val files = dir.listFiles() ?: return@forEach
                    if (files.any { it.canonicalFile != it.absoluteFile || !it.isFile }) return@forEach
                    if (files.filter { it != marker }.all { it.delete() }) {
                        marker.delete()
                        dir.delete()
                    }
                } catch (_: Exception) { /* Best effort; never interfere with publication. */ }
            }
        }
        fun validVideoId(id: String) = id.matches(Regex("[A-Za-z0-9_-]{1,128}"))

        internal fun child(root: File, name: String): File {
            val dir = root.canonicalFile
            val file = File(dir, name)
            require(file.canonicalFile.parentFile == dir && file.canonicalFile == file.absoluteFile) {
                "Invalid download path"
            }
            return file
        }

        /** Destination-volume capacity, including reclaimable cache, not primary-volume space. */
        fun availableSpace(context: Context, dir: File): Long = try {
            val storage = context.getSystemService(StorageManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                storage.getAllocatableBytes(storage.getUuidForPath(dir))
            } else {
                val volume = storage.getStorageVolume(dir)
                if (volume != null && volume.state != Environment.MEDIA_MOUNTED) -1L
                else StatFs(dir.absolutePath).availableBytes
            }
        } catch (_: Exception) { -1L }

        // Non-Android callers must inject a probe. Unknown capacity fails closed.
        fun availableSpace(@Suppress("UNUSED_PARAMETER") dir: File): Long = -1L

        internal fun isOutOfSpace(error: Throwable): Boolean = generateSequence(error) { it.cause }
            .take(8).any {
                val message = it.message.orEmpty().lowercase()
                message.contains("enospc") || message.contains("no space left") ||
                    message.contains("not enough space") || message.contains("insufficient storage")
            }
    }
}
