package com.sharl.musicapp.data

import android.content.Context
import android.util.Log

class PlaylistRepository(context: Context) {
    private val dao = AppDatabase.get(context).playlistDao()

    suspend fun create(name: String) = dao.createPlaylist(Playlist(name = name))
    fun all() = dao.allPlaylists()
    suspend fun delete(id: Long) { dao.deletePlaylistWithSongs(id) }

    suspend fun rename(id: Long, newName: String) {
        dao.rename(id, newName)
    }
    suspend fun addSong(
        playlistId: Long,
        videoId: String,
        title: String,
        artist: String,
        thumbnailUrl: String?
    ): Long {
        val rowId = dao.addSong(
            PlaylistSong(playlistId, videoId, title, artist, thumbnailUrl)
        )
        Log.d("PLAYLIST", "addSong playlistId=$playlistId videoId=$videoId rowId=$rowId")
        return rowId
    }
    suspend fun removeSong(playlistId: Long, videoId: String) =
        dao.removeSong(playlistId, videoId)
    fun songs(playlistId: Long) = dao.songsInPlaylist(playlistId)
}
