package com.sharl.musicapp.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.util.youtubeThumb

@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onLongClick: () -> Unit,
    showDownloaded: Boolean = false,
    isLoading: Boolean = false,
    onMenuClick: (() -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), 48.dp)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = buildString {
                append(song.artist)
                song.album?.let { append(" · $it") }
                append(" · ")
                append(song.playCount)
                append(" ")
                append(if (song.playCount == 1) "play" else "plays")
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (showDownloaded) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        IconButton(onClick = onToggleFavorite) {
            Text(
                text = if (song.isFavorite) "★" else "☆",
                style = MaterialTheme.typography.titleLarge
            )
        }
        if (onMenuClick != null) {
            IconButton(onClick = onMenuClick) {
                Text("⋮", style = MaterialTheme.typography.titleLarge)
            }
        }
        if (isLoading) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
    HorizontalDivider()
}
