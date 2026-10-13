package com.sharl.musicapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SpeedDialDao {
    @Query("SELECT * FROM speed_dial_slots ORDER BY position")
    fun allSlots(): Flow<List<SpeedDialSlot>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(slot: SpeedDialSlot)

    @Query("DELETE FROM speed_dial_slots WHERE position = :pos")
    suspend fun clear(pos: Int)

    @Query("SELECT COUNT(*) FROM speed_dial_slots")
    suspend fun count(): Int
}
