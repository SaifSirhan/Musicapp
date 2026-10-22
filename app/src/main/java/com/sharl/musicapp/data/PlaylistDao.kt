package com.sharl.musicapp.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    @Insert
    suspend fun createPlaylist(p: Playlist): Long

    @Query("SELECT * FROM playlists ORDER BY createdAt DESC")
    fun allPlaylists(): Flow<List<Playlist>>

    @Query("UPDATE playlists SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    @Query("DELETE FROM playlists")
    suspend fun clearAllPlaylists()

    // Phase 44: CrossRef-based queries only
    @Query("DELETE FROM playlist_song_cross_ref")
    suspend fun clearAllCrossRefs()

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :id")
    suspend fun clearCrossRefsForPlaylist(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(ref: PlaylistSongCrossRef): Long

    @Query("DELETE FROM playlist_song_cross_ref WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeSongFromPlaylist(playlistId: Long, videoId: String)

    /**
     * Phase 44: Get songs in a playlist via CrossRef, joining with Song table.
     * Returns full Song objects ordered by position.
     */
    @Transaction
    @Query("""
        SELECT s.* FROM songs s 
        INNER JOIN playlist_song_cross_ref ps ON s.videoId = ps.videoId 
        WHERE ps.playlistId = :playlistId 
        ORDER BY ps.position ASC
    """)
    fun songsInPlaylist(playlistId: Long): Flow<List<Song>>

    @Query("SELECT COUNT(*) FROM playlist_song_cross_ref WHERE playlistId = :playlistId")
    suspend fun countSongs(playlistId: Long): Int

    /**
     * Atomically wipe every playlist and every cross_ref row.
     */
    @Transaction
    suspend fun clearAll() {
        clearAllCrossRefs()
        clearAllPlaylists()
    }

    /**
     * Atomically delete a single playlist together with all of its cross_ref rows.
     */
    @Transaction
    suspend fun deletePlaylistWithSongs(id: Long) {
        clearCrossRefsForPlaylist(id)
        deletePlaylist(id)
    }
}
