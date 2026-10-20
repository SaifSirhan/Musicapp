package com.sharl.musicapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalMediaDao {
    @Query("SELECT * FROM local_media ORDER BY title COLLATE NOCASE")
    fun all(): Flow<List<LocalMedia>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<LocalMedia>)

    @Query("DELETE FROM local_media WHERE id NOT IN (:ids)")
    suspend fun deleteMissing(ids: List<String>)

    @Query("DELETE FROM local_media")
    suspend fun clearAll()
}
