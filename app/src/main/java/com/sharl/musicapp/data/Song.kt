package com.sharl.musicapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs",
    indices = [Index(value = ["normalizedTitle"])]
)
data class Song(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val trackNumber: Int? = null,
    val thumbnailUrl: String? = null,
    val lastPlayedAt: Long,
    val playCount: Int,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    val durationMs: Long = 0L,
    val normalizedTitle: String = "",
    val normalizedTitleVersion: Int = 0
)
