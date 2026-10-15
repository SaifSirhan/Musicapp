package com.sharl.musicapp.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.data.NewDrop
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.util.youtubeThumb

@Composable
fun NewDropCard(drop: NewDrop, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .padding(horizontal = 4.dp)
            .clickable { onClick() }
    ) {
        Box(
            Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(8.dp))
        ) {
            Thumbnail(drop.thumbnailUrl ?: youtubeThumb(drop.videoId), 140.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            drop.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            "New from ${drop.artist}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// Transient Song used only to reuse SongRow / the song menu for a not-yet-played drop.
fun NewDrop.asSong(): Song = Song(
    videoId = videoId,
    title = title,
    artist = artist,
    thumbnailUrl = thumbnailUrl ?: youtubeThumb(videoId),
    lastPlayedAt = 0L,
    playCount = 0,
    isFavorite = false,
    isHidden = false
)
