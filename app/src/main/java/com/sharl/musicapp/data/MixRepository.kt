package com.sharl.musicapp.data

import android.content.Context
import android.util.Log
import com.sharl.musicapp.YtdlpHelper

class MixRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(context).songDao()

    // Phase 47: mixes used to return just the seed. Build neighbours from the
    // local library, and fall back to a yt-dlp web search when the library is
    // too small to make a varied mix (results show a download icon in the queue).
    suspend fun generateMix(seed: Song): List<Song> {
        val local = buildLocalMix(seed)
        val librarySize = dao.count()

        if (librarySize >= MIN_LOCAL_SONGS) {
            Log.d("MIX", "local mix for ${seed.videoId}: ${local.size} tracks")
            return local
        }

        Log.d("MIX", "library has $librarySize songs; falling back to web mix")
        val web = try {
            YtdlpHelper.searchYouTube(
                appContext,
                "${seed.artist} ${seed.title} mix".trim(),
                limit = 15
            )
        } catch (e: Exception) {
            Log.e("MIX", "web mix search failed: ${e.message}", e)
            emptyList()
        }

        val webSongs = web
            .filter { it.videoId != seed.videoId }
            .map {
                Song(
                    videoId = it.videoId,
                    title = it.title,
                    artist = seed.artist,
                    thumbnailUrl = "https://i.ytimg.com/vi/${it.videoId}/hqdefault.jpg",
                    lastPlayedAt = 0L,
                    playCount = 0
                )
            }

        return when {
            webSongs.isNotEmpty() -> listOf(seed) + webSongs
            local.size > 1 -> local
            else -> listOf(seed)
        }
    }

    private suspend fun buildLocalMix(seed: Song): List<Song> {
        val sameArtist = if (seed.artist.isBlank()) emptyList() else {
            dao.randomSongsByArtist(seed.artist, 8)
        }
        val filler = dao.randomSongs(15, seed.videoId)
        return (listOf(seed) + sameArtist + filler)
            .distinctBy { it.videoId }
            .take(15)
    }

    private companion object {
        const val MIN_LOCAL_SONGS = 10
    }
}
