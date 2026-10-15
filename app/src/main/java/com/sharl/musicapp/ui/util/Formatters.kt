package com.sharl.musicapp.ui.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(s / 60, s % 60)
}

fun youtubeThumb(videoId: String): String =
    "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"

fun formatDate(epochMs: Long): String {
    val sdf = SimpleDateFormat("MMM yyyy", Locale.getDefault())
    return sdf.format(Date(epochMs))
}

// Parses a PostgREST timestamptz string (e.g. "2026-10-13T10:31:00.123456+00:00").
fun parseIsoMs(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    return try {
        val noFrac = iso.replace(Regex("\\.\\d+"), "")
        val normalized = noFrac
            .replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
            .replace("Z", "+0000")
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.getDefault())
            .parse(normalized)?.time
    } catch (_: Exception) {
        null
    }
}

fun formatRelative(iso: String?): String {
    val ms = parseIsoMs(iso) ?: return "just now"
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000 -> "just now"
        diff < 3_600_000 -> "${diff / 60_000} min ago"
        diff < 86_400_000 -> "${diff / 3_600_000} h ago"
        else -> "${diff / 86_400_000} d ago"
    }
}

fun formatBytes(b: Long): String {
    if (b < 1024) return "$b B"
    val kb = b / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}
