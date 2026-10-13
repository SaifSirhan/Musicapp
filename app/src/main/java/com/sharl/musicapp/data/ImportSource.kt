package com.sharl.musicapp.data

import android.net.Uri

sealed class ImportSource {
    data class YtmUrl(val url: String) : ImportSource()
    data class SpotifyUrl(val url: String) : ImportSource()
    data class TextPaste(val blob: String) : ImportSource()
    data class CsvFile(val uri: Uri) : ImportSource()
}

data class ImportTrack(
    val title: String,
    val artist: String,
    val videoId: String? = null,   // filled for YTM imports, null until resolved
    val thumbnailUrl: String? = null
)

data class ImportPreview(
    val sourceName: String,                // e.g. "My Summer Mix"
    val tracks: List<ImportTrack>,
    val unresolved: List<ImportTrack> = emptyList()
)

// Thrown for user-facing import failures (private playlist, unreadable file, etc.).
class ImportException(message: String) : Exception(message)
