package com.sharl.musicapp.data

data class NewDrop(
    val videoId: String,
    val title: String,
    val artist: String,       // the seed artist this result came from
    val uploader: String?,    // yt-dlp's uploader/channel, if present
    val thumbnailUrl: String?,
    val durationMs: Long = 0L
)
