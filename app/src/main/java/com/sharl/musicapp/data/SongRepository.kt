package com.sharl.musicapp.data

import android.content.Context

class SongRepository(context: Context) {
    private val dao = AppDatabase.get(context).songDao()

    suspend fun recordPlay(
        videoId: String,
        title: String,
        artist: String,
        album: String? = null
    ) {
        val existing = dao.getById(videoId)
        val now = System.currentTimeMillis()
        dao.upsert(
            Song(
                videoId = videoId,
                title = title,
                artist = artist,
                album = album,
                lastPlayedAt = now,
                playCount = (existing?.playCount ?: 0) + 1,
                isFavorite = existing?.isFavorite ?: false,
                isHidden = existing?.isHidden ?: false
            )
        )
    }

    fun recentlyPlayed() = dao.recentlyPlayed()

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
}
