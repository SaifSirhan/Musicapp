package com.sharl.musicapp.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Phase 44: Junction table for Many-to-Many relationship between Playlists and Songs.
 * Uses ForeignKeys with CASCADE delete to maintain referential integrity.
 */
@Entity(
    tableName = "playlist_song_cross_ref",
    primaryKeys = ["playlistId", "videoId"],
    foreignKeys = [
        ForeignKey(
            entity = Playlist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Song::class,
            parentColumns = ["videoId"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("videoId")]
)
data class PlaylistSongCrossRef(
    val playlistId: Long,
    val videoId: String,
    val position: Int = 0,
    val addedAt: Long = System.currentTimeMillis()
)
