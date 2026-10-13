package com.sharl.musicapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "downloads")
data class Download(
    @PrimaryKey val videoId: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val downloadedAt: Long,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    val durationMs: Long = 0L
)
