package com.sharl.musicapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.data.SpeedDialSlot
import com.sharl.musicapp.ui.util.youtubeThumb

@Composable
fun SpeedDialTile(
    slot: SpeedDialSlot?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    showDownloaded: Boolean = false
) {
    val bg = if (slot == null || slot.type == "empty")
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    else
        MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center
    ) {
        when (slot?.type) {
            "song" -> {
                if (slot.thumbnailUrl != null || slot.refId != null) {
                    Thumbnail(
                        slot.thumbnailUrl ?: youtubeThumb(slot.refId ?: ""),
                        200.dp  // clipped by parent
                    )
                }
                // Overlay title at the bottom
                Column(
                    modifier = Modifier.fillMaxSize().padding(6.dp),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    Text(
                        slot.title ?: "",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            "playlist" -> {
                Text(
                    slot.title ?: "Playlist",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(6.dp)
                )
            }
            "shuffle_fav" -> {
                Text(
                    "🔀 Favorites",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
            }
            "shuffle_recent" -> {
                Text(
                    "🔀 Recent",
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center
                )
            }
            else -> {
                Text(
                    "+",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (showDownloaded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
            ) {
                Text(
                    "✓",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
