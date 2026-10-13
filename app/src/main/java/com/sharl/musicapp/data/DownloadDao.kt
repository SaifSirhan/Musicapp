package com.sharl.musicapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun all(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE videoId = :vid LIMIT 1")
    suspend fun get(vid: String): Download?

    @Query("SELECT videoId FROM downloads")
    fun allIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: Download)

    @Query("DELETE FROM downloads WHERE videoId = :vid")
    suspend fun delete(vid: String)

    @Query("SELECT SUM(fileSizeBytes) FROM downloads")
    fun totalBytes(): Flow<Long?>
}
