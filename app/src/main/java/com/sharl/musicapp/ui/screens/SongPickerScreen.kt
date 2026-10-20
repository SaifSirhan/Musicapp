package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.components.SongRow

@Composable
fun SongPickerScreen(
    recent: List<Song>,
    favorites: List<Song>,
    allSongs: List<Song>,
    onBack: () -> Unit,
    onPick: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit = {}
) {
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf(0) }  // 0 = Recent, 1 = Favorites, 2 = All

    val source = when (filter) {
        1 -> favorites
        2 -> allSongs
        else -> recent
    }
    val shown = remember(source, query) {
        val q = query.trim()
        val matched = if (q.isBlank()) source else source.filter {
            it.title.contains(q, ignoreCase = true) || it.artist.contains(q, ignoreCase = true)
        }
        matched.distinctBy { it.videoId }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Text("←", style = MaterialTheme.typography.headlineMedium)
            }
            Spacer(Modifier.width(8.dp))
            Text("Select a song", style = MaterialTheme.typography.headlineSmall)
        }

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search songs, artists…") },
            singleLine = true,
            leadingIcon = {
                Text("🔍", modifier = Modifier.padding(start = 12.dp))
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Text("✕", style = MaterialTheme.typography.titleMedium)
                    }
                }
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = filter == 0,
                onClick = { filter = 0 },
                label = { Text("Recently Played") }
            )
            FilterChip(
                selected = filter == 1,
                onClick = { filter = 1 },
                label = { Text("Favorites") }
            )
            FilterChip(
                selected = filter == 2,
                onClick = { filter = 2 },
                label = { Text("All Songs") }
            )
        }

        if (shown.isEmpty()) {
            Text(
                if (query.isBlank()) "No songs here yet." else "No matches.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(shown, key = { it.videoId }) { song ->
                    SongRow(
                        song = song,
                        onClick = { onPick(song) },
                        onToggleFavorite = { onToggleFavorite(song) },
                        onLongClick = {}
                    )
                }
            }
        }
    }
}
