package com.sharl.musicapp

import java.util.UUID

/** One occurrence, not one song: duplicates deliberately have distinct identities. */
data class QueueItem(
    val videoId: String,
    val title: String,
    val artist: String = "",
    val localUri: String? = null,
    val isVideo: Boolean = false,
    val uid: String = "$videoId|${UUID.randomUUID()}",
)
