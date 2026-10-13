package com.sharl.musicapp.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

sealed class ImportState {
    object Idle : ImportState()
    data class Resolving(val done: Int, val total: Int) : ImportState()
    data class Previewing(val preview: ImportPreview) : ImportState()
    data class Committing(val done: Int, val total: Int) : ImportState()
    data class Done(val playlistId: Long, val imported: Int, val skipped: Int) : ImportState()
    data class Error(val message: String) : ImportState()
}

class ImportRepository(private val context: Context) {

    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state

    suspend fun preview(source: ImportSource): ImportPreview {
        _state.value = ImportState.Resolving(0, 0)
        return try {
            val result = ImportResolver(context).resolve(source) { done, total ->
                _state.value = ImportState.Resolving(done, total)
            }
            _state.value = ImportState.Previewing(result)
            result
        } catch (e: Exception) {
            _state.value = ImportState.Error(e.message ?: "Import failed.")
            ImportPreview(sourceName = "", tracks = emptyList())
        }
    }

    suspend fun commit(preview: ImportPreview): Long = withContext(Dispatchers.IO) {
        val total = preview.tracks.size
        _state.value = ImportState.Committing(0, total)
        return@withContext try {
            val playlistRepo = PlaylistRepository(context)
            val songDao = AppDatabase.get(context).songDao()
            val playlistId = playlistRepo.create(preview.sourceName)
            var imported = 0
            for ((i, track) in preview.tracks.withIndex()) {
                val vid = track.videoId
                if (vid != null) {
                    if (songDao.getById(vid) == null) {
                        songDao.upsert(
                            Song(
                                videoId = vid,
                                title = track.title,
                                artist = track.artist,
                                thumbnailUrl = track.thumbnailUrl,
                                lastPlayedAt = 0L,
                                playCount = 0,
                                isFavorite = false,
                                isHidden = false
                            )
                        )
                    }
                    playlistRepo.addSong(playlistId, vid, track.title, track.artist, track.thumbnailUrl)
                    imported++
                }
                _state.value = ImportState.Committing(i + 1, total)
            }
            _state.value = ImportState.Done(playlistId, imported, preview.unresolved.size)
            playlistId
        } catch (e: Exception) {
            _state.value = ImportState.Error(e.message ?: "Import failed.")
            -1L
        }
    }

    fun reset() {
        _state.value = ImportState.Idle
    }
}
