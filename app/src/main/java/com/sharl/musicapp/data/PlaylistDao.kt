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

    @Query("DELETE FROM playlist_songs")
    suspend fun clearAllPlaylistSongs()

    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun clearPlaylistSongs(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSong(ps: PlaylistSong): Long

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND videoId = :videoId")
    suspend fun removeSong(playlistId: Long, videoId: String)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY addedAt DESC")
    fun songsInPlaylist(playlistId: Long): Flow<List<PlaylistSong>>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun countSongs(playlistId: Long): Int

    /**
     * Atomically wipe every playlist and every playlist_songs row so that no
     * orphaned playlist_songs entries are left behind after the parent
     * playlists table is cleared.
     */
    @Transaction
    suspend fun clearAll() {
        clearAllPlaylistSongs()
        clearAllPlaylists()
    }

    /**
     * Atomically delete a single playlist together with all of its
     * playlist_songs rows, avoiding the orphaned-row bug caused by two
     * independent non-transactional DAO calls.
     */
    @Transaction
    suspend fun deletePlaylistWithSongs(id: Long) {
        clearPlaylistSongs(id)
        deletePlaylist(id)
    }
}
