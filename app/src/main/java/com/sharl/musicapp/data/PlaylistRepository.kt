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
        // Phase 44: Add to CrossRef with position based on count
        val position = dao.countSongs(playlistId)
        val ref = PlaylistSongCrossRef(playlistId, videoId, position)
        val rowId = dao.addSongToPlaylist(ref)
        Log.d("PLAYLIST", "addSong playlistId=$playlistId videoId=$videoId rowId=$rowId")
        return rowId
    }
    suspend fun removeSong(playlistId: Long, videoId: String) =
        dao.removeSongFromPlaylist(playlistId, videoId)
    fun songs(playlistId: Long) = dao.songsInPlaylist(playlistId)
}
