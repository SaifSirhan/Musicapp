package com.sharl.musicapp.data

import android.content.Context

class SongRepository(context: Context) {
    private val dao = AppDatabase.get(context).songDao()
    
    // Phase 44d: Expose DAO for artist queries in LibraryTab
    fun getDao() = dao

    suspend fun recordPlay(
        videoId: String,
        title: String,
        artist: String,
        album: String? = null,
        thumbnailUrl: String? = null
    ) {
        val existing = dao.getById(videoId)
        val now = System.currentTimeMillis()
        dao.upsert(
            Song(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album ?: existing?.album,
                trackNumber = existing?.trackNumber,
                thumbnailUrl = thumbnailUrl ?: existing?.thumbnailUrl,
                lastPlayedAt = now,
                playCount = (existing?.playCount ?: 0) + 1,
                isFavorite = existing?.isFavorite ?: false,
                isHidden = existing?.isHidden ?: false
            )
        )
    }

    suspend fun saveDownloadedSong(
        videoId: String,
        title: String,
        artist: String,
        album: String?,
        thumbnailUrl: String?,
        trackNumber: Int? = null
    ) {
        val existing = dao.getById(videoId)
        dao.upsert(
            Song(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album ?: existing?.album,
                trackNumber = trackNumber ?: existing?.trackNumber,
                thumbnailUrl = thumbnailUrl ?: existing?.thumbnailUrl,
                lastPlayedAt = existing?.lastPlayedAt ?: 0L,
                playCount = existing?.playCount ?: 0,
                isFavorite = existing?.isFavorite ?: false,
                isHidden = existing?.isHidden ?: false
            )
        )
    }

    fun recentlyPlayed() = dao.recentlyPlayed()

    suspend fun get(videoId: String): Song? = dao.getById(videoId)

    suspend fun toggleFavorite(song: Song) {
        val existing = dao.getById(song.videoId)
        if (existing == null) {
            dao.upsert(song.copy(isFavorite = !song.isFavorite))
        } else {
            dao.setFavorite(song.videoId, !existing.isFavorite)
        }
    }

    suspend fun hide(song: Song) {
        dao.hide(song.videoId)
    }

    fun favorites() = dao.favorites()

    fun recentSongs(limit: Int = 20) = dao.recentSongs(limit)
    fun mostPlayed(limit: Int = 20) = dao.mostPlayed(limit)
    fun songsByArtist(artist: String, limit: Int = 20) = dao.songsByArtist(artist, limit)
    fun allSongs() = dao.allSongs()
    fun favoriteSongs(limit: Int = 20) = dao.favoriteSongs(limit)
    fun topArtist() = dao.topArtist()
    suspend fun searchLocal(q: String) = dao.searchLocal(q)
}
