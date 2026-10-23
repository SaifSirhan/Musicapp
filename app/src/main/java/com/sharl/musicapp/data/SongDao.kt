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

    // Phase 44d: Get all songs by artist (no limit) for ArtistDetailScreen
    @Query("SELECT * FROM songs WHERE isHidden = 0 AND artist = :artistName ORDER BY lastPlayedAt DESC")
    fun getSongsByArtist(artistName: String): Flow<List<Song>>

    @Query("""
        SELECT * FROM songs
        WHERE isHidden = 0 AND artist = :artist AND album = :album
        ORDER BY CASE WHEN trackNumber IS NULL THEN 1 ELSE 0 END,
                 trackNumber,
                 title COLLATE NOCASE
    """)
    fun getSongsByArtistAndAlbum(artist: String, album: String): Flow<List<Song>>

    @Query("""
        SELECT DISTINCT album FROM songs
        WHERE isHidden = 0 AND artist = :artist
          AND album IS NOT NULL AND album != ''
        ORDER BY album COLLATE NOCASE
    """)
    fun albumsByArtist(artist: String): Flow<List<String>>

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

    @Query("SELECT * FROM songs WHERE isHidden = 0 AND videoId != :exclude ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomSongs(limit: Int, exclude: String): List<Song>

    @Query("SELECT videoId FROM songs WHERE isHidden = 1")
    suspend fun hiddenIds(): List<String>

    @Query("""
        SELECT * FROM songs
        WHERE isHidden = 0 AND album = :album AND album IS NOT NULL AND album != ''
        ORDER BY title COLLATE NOCASE
        LIMIT :limit
    """)
    suspend fun songsByAlbum(album: String, limit: Int): List<Song>

    @Query("""
        SELECT album FROM songs
        WHERE isHidden = 0 AND album IS NOT NULL AND album != ''
        GROUP BY album
        ORDER BY RANDOM()
        LIMIT 1
    """)
    suspend fun randomAlbum(): String?

    // Phase 50: same normalized title + artist within a duration tolerance is a
    // duplicate identity. Unknown durations are never treated as duplicates.
    @Query("""
        SELECT * FROM songs
        WHERE isHidden = 0
          AND LOWER(artist) = LOWER(:artist)
          AND normalizedTitle = :normalizedTitle
          AND normalizedTitleVersion > 0
          AND durationMs > 0
          AND :durationMs > 0
          AND ABS(durationMs - :durationMs) <= :toleranceMs
        ORDER BY ABS(durationMs - :durationMs), playCount DESC
        LIMIT 1
    """)
    suspend fun findNearDuplicate(
        artist: String,
        normalizedTitle: String,
        durationMs: Long,
        toleranceMs: Long
    ): Song?

    @Query("""
        SELECT * FROM songs
        WHERE normalizedTitle = :normalizedTitle
          AND normalizedTitleVersion > 0
          AND isHidden = 0
        ORDER BY lastPlayedAt DESC
        LIMIT :limit
    """)
    suspend fun findByNormalizedTitle(normalizedTitle: String, limit: Int): List<Song>

    @Query("""
        SELECT * FROM songs
        WHERE normalizedTitleVersion < :version
        ORDER BY videoId
        LIMIT :limit
    """)
    suspend fun songsNeedingNormalization(version: Int, limit: Int): List<Song>

    @Query("""
        UPDATE songs
        SET normalizedTitle = :normalizedTitle, normalizedTitleVersion = :version
        WHERE videoId = :videoId
    """)
    suspend fun setNormalization(videoId: String, normalizedTitle: String, version: Int)

    @Query("UPDATE songs SET durationMs = :durationMs WHERE videoId = :videoId")
    suspend fun setDuration(videoId: String, durationMs: Long)

    @Query("""
        UPDATE songs
        SET durationMs = (
            SELECT d.durationMs FROM downloads d WHERE d.videoId = songs.videoId LIMIT 1
        )
        WHERE durationMs = 0
          AND EXISTS (
              SELECT 1 FROM downloads d
              WHERE d.videoId = songs.videoId AND d.durationMs > 0
          )
    """)
    suspend fun backfillDurationsFromDownloads(): Int
}
