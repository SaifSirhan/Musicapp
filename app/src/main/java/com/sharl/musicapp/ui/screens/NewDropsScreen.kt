package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.data.NewDrop
import com.sharl.musicapp.ui.components.SongRow
import com.sharl.musicapp.ui.components.asSong

@Composable
fun NewDropsScreen(
    drops: List<NewDrop>,
    loading: Boolean,
    downloadIds: List<String>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (NewDrop) -> Unit,
    onLongClick: (NewDrop) -> Unit,
    onToggleFavorite: (NewDrop) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onBack) {
                    Text("←", style = MaterialTheme.typography.titleLarge)
                }
            }
            Text(
                "New drops",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(4.dp))
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = onRefresh) {
                    Text("⟳", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        if (drops.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No new drops right now. Play a few songs or favorite some artists and pull back here.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(drops, key = { it.videoId }) { drop ->
                    SongRow(
                        song = drop.asSong(),
                        onClick = { onPlay(drop) },
                        onToggleFavorite = { onToggleFavorite(drop) },
                        onLongClick = { onLongClick(drop) },
                        showDownloaded = downloadIds.contains(drop.videoId)
                    )
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }
}
