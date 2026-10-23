package com.sharl.musicapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable record of a download request. Rows survive process death so interrupted
 * work can be resumed and partially published audio can be completed on restart.
 */
@Entity(tableName = "download_jobs", indices = [Index(value = ["status"])])
data class DownloadJob(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long,
    val status: String,
    val attempts: Int,
    val requestedAt: Long,
    val updatedAt: Long,
    val lastError: String? = null,
    val destinationDir: String? = null,
    val album: String? = null,
    val trackNumber: Int? = null
) {
    object Status {
        const val QUEUED = "QUEUED"
        const val DOWNLOADING = "DOWNLOADING"
        const val WAITING_FOR_SPACE = "WAITING_FOR_SPACE"
        const val COMPLETED = "COMPLETED"
        const val FAILED = "FAILED"
    }
}
