package com.sharl.musicapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.util.youtubeThumb

@Composable
fun <T> HorizontalCarousel(
    items: List<T>,
    key: (T) -> Any,
    content: @Composable (T) -> Unit
) {
    if (items.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        this.items(items, key = key) { item -> content(item) }
    }
}

@Composable
fun HorizontalCarousel(
    title: String,
    songs: List<Song>,
    onSongClick: (Song) -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    if (songs.isEmpty()) return
    Column(modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(bottom = 6.dp)
            )
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) {
                    Text(actionLabel)
                }
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp)
        ) {
            items(songs, key = { it.videoId }) { song ->
                Column(
                    modifier = Modifier
                        .width(100.dp)
                        .padding(end = 8.dp)
                        .clickable { onSongClick(song) }
                ) {
                    Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), 100.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        song.artist,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
