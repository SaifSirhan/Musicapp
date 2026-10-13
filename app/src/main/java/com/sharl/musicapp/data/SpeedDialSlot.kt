package com.sharl.musicapp.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "speed_dial_slots")
data class SpeedDialSlot(
    @PrimaryKey val position: Int,  // 0..44
    val type: String,               // "song" | "playlist" | "shuffle_fav" |
                                    // "shuffle_recent" | "empty"
    val refId: String? = null,      // videoId for song, playlistId-as-string for playlist
    val title: String? = null,
    val thumbnailUrl: String? = null
)
