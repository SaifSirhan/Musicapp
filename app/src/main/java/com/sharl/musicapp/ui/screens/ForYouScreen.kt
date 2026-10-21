package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.QueueItem
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.components.RecommendationRow

@Composable
fun ForYouScreen(
    forYouRecent: List<Song>,
    forYouTop: List<Song>,
    topArtistName: String?,
    forYouByArtist: List<Song>,
    forYouFavorites: List<Song>,
    playFromQueue: (List<QueueItem>, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
    ) {
        item {
            RecommendationRow(
                title = "Recently Played",
                songs = forYouRecent,
                onSongClick = { song ->
                    playFromQueue(
                        forYouRecent.map { QueueItem(it.videoId, it.title, it.artist) },
                        forYouRecent.indexOf(song)
                    )
                }
            )
        }
        item {
            RecommendationRow(
                title = "Most Played",
                songs = forYouTop,
                onSongClick = { song ->
                    playFromQueue(
                        forYouTop.map { QueueItem(it.videoId, it.title, it.artist) },
                        forYouTop.indexOf(song)
                    )
                }
            )
        }
        item {
            if (topArtistName != null) {
                RecommendationRow(
                    title = "More from $topArtistName",
                    songs = forYouByArtist,
                    onSongClick = { song ->
                        playFromQueue(
                            forYouByArtist.map { QueueItem(it.videoId, it.title, it.artist) },
                            forYouByArtist.indexOf(song)
                        )
                    }
                )
            }
        }
        item {
            RecommendationRow(
                title = "Your Favorites",
                songs = forYouFavorites,
                onSongClick = { song ->
                    playFromQueue(
                        forYouFavorites.map { QueueItem(it.videoId, it.title, it.artist) },
                        forYouFavorites.indexOf(song)
                    )
                }
            )
        }
        if (forYouRecent.isEmpty() && forYouTop.isEmpty() && forYouFavorites.isEmpty()) {
            item {
                Text(
                    text = "Play some songs and they'll show up here.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
