package com.sharl.musicapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface DownloadJobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsert(job: DownloadJob)

    @Query("SELECT * FROM download_jobs WHERE videoId = :videoId LIMIT 1")
    suspend fun get(videoId: String): DownloadJob?

    @Query("SELECT * FROM download_jobs WHERE status IN ('QUEUED', 'WAITING_FOR_SPACE') ORDER BY requestedAt")
    suspend fun resumable(): List<DownloadJob>

    @Query("SELECT * FROM download_jobs WHERE status = 'COMPLETED' ORDER BY requestedAt")
    suspend fun completed(): List<DownloadJob>

    @Query("SELECT * FROM download_jobs WHERE status = :status ORDER BY requestedAt")
    suspend fun byStatus(status: String): List<DownloadJob>

    @Query("""
        UPDATE download_jobs SET status = 'DOWNLOADING', attempts = attempts + 1,
        updatedAt = :now, lastError = NULL
        WHERE videoId = :videoId AND status IN ('QUEUED', 'WAITING_FOR_SPACE', 'FAILED')
    """)
    suspend fun compareAndSetClaim(videoId: String, now: Long): Int

    @Transaction
    suspend fun claim(job: DownloadJob, now: Long): DownloadJob? {
        upsert(job)
        return if (compareAndSetClaim(job.videoId, now) == 1) get(job.videoId) else null
    }

    @Query("""
        UPDATE download_jobs
        SET status = :status, updatedAt = :updatedAt, lastError = :lastError
        WHERE videoId = :videoId AND attempts = :attempts AND status = 'DOWNLOADING'
    """)
    suspend fun finish(videoId: String, status: String, attempts: Int, updatedAt: Long, lastError: String?): Int

    @Query("""
        UPDATE download_jobs
        SET status = :status, attempts = :attempts, updatedAt = :updatedAt, lastError = :lastError
        WHERE videoId = :videoId AND status != 'DOWNLOADING'
    """)
    suspend fun setStatus(videoId: String, status: String, attempts: Int, updatedAt: Long, lastError: String?)

    // Caller MUST hold the destination queue's OS file lock. Timeouts cannot prove owner death.
    @Query("UPDATE download_jobs SET status = 'QUEUED', updatedAt = :updatedAt WHERE status = 'DOWNLOADING'")
    suspend fun requeueRunning(updatedAt: Long): Int

    @Query("UPDATE download_jobs SET destinationDir = :destinationDir WHERE videoId = :videoId AND destinationDir IS NULL")
    suspend fun bindDestination(videoId: String, destinationDir: String)

    @Query("DELETE FROM download_jobs WHERE videoId = :videoId AND status IN ('QUEUED', 'WAITING_FOR_SPACE', 'FAILED')")
    suspend fun discardQueued(videoId: String): Int

    @Query("DELETE FROM download_jobs WHERE videoId = :videoId AND status = 'COMPLETED'")
    suspend fun delete(videoId: String)
}
