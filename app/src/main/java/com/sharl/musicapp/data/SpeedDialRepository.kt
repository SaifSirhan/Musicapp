package com.sharl.musicapp.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class SpeedDialRepository(context: Context) {
    private val dao = AppDatabase.get(context).speedDialDao()

    fun allSlots(): Flow<List<SpeedDialSlot>> = dao.allSlots()

    suspend fun assignSong(position: Int, videoId: String, title: String, thumb: String?) =
        dao.upsert(SpeedDialSlot(position, "song", videoId, title, thumb))

    suspend fun assignPlaylist(position: Int, playlistId: Long, name: String) =
        dao.upsert(SpeedDialSlot(position, "playlist", playlistId.toString(), name, null))

    suspend fun assignShuffleFavorites(position: Int) =
        dao.upsert(SpeedDialSlot(position, "shuffle_fav", null, "Shuffle Favorites", null))

    suspend fun assignShuffleRecent(position: Int) =
        dao.upsert(SpeedDialSlot(position, "shuffle_recent", null, "Shuffle Recently Played", null))

    suspend fun clear(position: Int) = dao.clear(position)

    suspend fun seedIfEmpty(recentSongs: List<Song>) {
        if (dao.count() > 0) return
        recentSongs.take(9).forEachIndexed { idx, song ->
            dao.upsert(
                SpeedDialSlot(
                    position = idx,
                    type = "song",
                    refId = song.videoId,
                    title = song.title,
                    thumbnailUrl = song.thumbnailUrl
                )
            )
        }
    }
}
