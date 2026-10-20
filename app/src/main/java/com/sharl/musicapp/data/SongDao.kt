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

    @Query("SELECT * FROM songs WHERE lastPlayedAt > 0 AND isHidden = 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun recentlyPlayed(limit: Int = 50): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE videoId = :videoId LIMIT 1")
    suspend fun getById(videoId: String): Song?

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun count(): Int

    @Query("UPDATE songs SET isFavorite = :fav WHERE videoId = :videoId")
    suspend fun setFavorite(videoId: String, fav: Boolean)

    @Query("UPDATE songs SET isHidden = 1 WHERE videoId = :videoId")
    suspend fun hide(videoId: String)

    @Query("DELETE FROM songs")
    suspend fun clearAll()

    @Query("SELECT * FROM songs WHERE isFavorite = 1 AND isHidden = 0 ORDER BY lastPlayedAt DESC")
    fun favorites(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND lastPlayedAt > 0 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun recentSongs(limit: Int): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND playCount > 0 ORDER BY playCount DESC LIMIT :limit")
    fun mostPlayed(limit: Int): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND artist = :artist ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun songsByArtist(artist: String, limit: Int): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 ORDER BY title COLLATE NOCASE")
    fun allSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND isFavorite = 1 ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun favoriteSongs(limit: Int): Flow<List<Song>>

    @Query("SELECT artist FROM songs WHERE isHidden = 0 AND playCount > 0 GROUP BY artist ORDER BY SUM(playCount) DESC LIMIT 1")
    fun topArtist(): Flow<String?>

    @Query("""
        SELECT * FROM songs
        WHERE title LIKE '%' || :q || '%'
           OR artist LIKE '%' || :q || '%'
        ORDER BY playCount DESC
        LIMIT 10
    """)
    suspend fun searchLocal(q: String): List<Song>

    @Query("""
        SELECT artist FROM songs
        WHERE isHidden = 0 AND playCount > 0 AND artist != ''
          AND (:exclude = '' OR LOWER(artist) != LOWER(:exclude))
        GROUP BY artist
        ORDER BY SUM(playCount) DESC
        LIMIT :limit
    """)
    suspend fun topArtists(limit: Int, exclude: String): List<String>

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND artist = :artist ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomSongsByArtist(artist: String, limit: Int): List<Song>
}
