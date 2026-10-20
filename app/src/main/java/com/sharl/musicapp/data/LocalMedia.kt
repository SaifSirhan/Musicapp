package com.sharl.musicapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_media")
data class LocalMedia(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val isVideo: Boolean
)
