package com.sharl.musicapp.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index("playlistId")]
)
data class PlaylistSong(
    val playlistId: Long,
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val addedAt: Long = System.currentTimeMillis()
)
