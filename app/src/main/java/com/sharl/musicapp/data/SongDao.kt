package com.sharl.musicapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: Song)

    @Query("SELECT * FROM songs ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun recentlyPlayed(limit: Int = 50): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE videoId = :videoId LIMIT 1")
    suspend fun getById(videoId: String): Song?

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int
}
