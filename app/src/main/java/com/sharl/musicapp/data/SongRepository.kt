package com.sharl.musicapp.data

import android.content.Context

class SongRepository(context: Context) {
    private val dao = AppDatabase.get(context).songDao()

    suspend fun recordPlay(videoId: String, title: String, artist: String) {
        val existing = dao.getById(videoId)
        val now = System.currentTimeMillis()
        dao.upsert(
            Song(
                videoId = videoId,
                title = title,
                artist = artist,
                lastPlayedAt = now,
                playCount = (existing?.playCount ?: 0) + 1
            )
        )
    }

    fun recentlyPlayed() = dao.recentlyPlayed()
}
