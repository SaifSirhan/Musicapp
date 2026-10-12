package com.sharl.musicapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "songs")
data class Song(
    @PrimaryKey val videoId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val lastPlayedAt: Long,
    val playCount: Int,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false
)
