package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.QueueItem
import com.sharl.musicapp.data.NewDrop
import com.sharl.musicapp.data.NowPlaying
import com.sharl.musicapp.data.Profile
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.data.SpeedDialSlot
import com.sharl.musicapp.ui.components.FriendsStrip
import com.sharl.musicapp.ui.components.HorizontalCarousel
import com.sharl.musicapp.ui.components.NewDropCard
import com.sharl.musicapp.ui.components.SpeedDialTile
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.util.youtubeThumb

// Home tab (tab 0), extracted verbatim from PlayerScreen. All captured state
// arrives as parameters — no ViewModel, per the Phase 16 constraints. It is a
// ColumnScope extension because the root LazyColumn consumes weight(1f) from
// the PlayerScreen column.
@Composable
fun ColumnScope.HomeTab(
    forYouTop: List<Song>,
    forYouRecent: List<Song>,
    onPlayFromQueue: (List<QueueItem>, Int) -> Unit,
    onSongMix: (Song) -> Unit,
    downloadIds: List<String>,
    loadingVideoId: String?,
    onSongMenu: (Song) -> Unit,
    signedIn: Boolean,
    friendsNowPlaying: List<NowPlaying>,
    friendsList: List<Profile>,
    onFriendClick: (NowPlaying) -> Unit,
    newDrops: List<NewDrop>,
    newDropsLoading: Boolean,
    onSeeAllDrops: () -> Unit,
    speedDialSlots: List<SpeedDialSlot>,
    onSpeedDialTap: (SpeedDialSlot?, Int) -> Unit,
    onSpeedDialLongPress: (Int) -> Unit,
    topArtistName: String?,
    forYouByArtist: List<Song>,
    forYouFavorites: List<Song>,
    isFollowingTopArtist: Boolean = false,
    onToggleFollowTopArtist: () -> Unit = {},
    onDiscoverUnderground: () -> Unit = {},
    undergroundLoading: Boolean = false,
) {
    val quickPicks30 = androidx.compose.runtime.remember(forYouTop, forYouRecent) {
        val combined = (forYouTop + forYouRecent).distinctBy { it.videoId }
        combined.take(30)
    }
    // Strict 5-per-page pagination: the pager count and each page's items come
    // from the same chunk list, so page 1 can never spill into page 2.
    val quickPickChunks = androidx.compose.runtime.remember(quickPicks30) {
        quickPicks30.chunked(5)
    }
    val quickPickPages = quickPickChunks.size
    LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = 8.dp)
        ) {
            item {
                if (quickPicks30.isNotEmpty()) {
                    Text(
                        "Quick picks",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge.copy(
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                        ),
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
                    )
                    val quickPagerState = androidx.compose.foundation.pager.rememberPagerState(
                        pageCount = { quickPickPages }
                    )
                    androidx.compose.foundation.pager.HorizontalPager(
                        state = quickPagerState,
                        modifier = Modifier.fillMaxWidth()
                    ) { page ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            val pageItems = quickPickChunks.getOrElse(page) { emptyList() }
                            pageItems.forEach { song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { onSongMix(song) }
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Thumbnail(song.thumbnailUrl ?: youtubeThumb(song.videoId), 56.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            song.title.ifBlank { "Unknown Track" },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            style = androidx.compose.material3.MaterialTheme.typography.bodyLarge.copy(
                                                fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                                            ),
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            "${song.artist} · ${song.playCount} " +
                                            if (song.playCount == 1) "play" else "plays",
                                            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (downloadIds.contains(song.videoId)) {
                                        Text(
                                            "✓",
                                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                                            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.padding(horizontal = 4.dp)
                                        )
                                    }
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier.size(48.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (song.videoId == loadingVideoId) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier.size(20.dp),
                                                strokeWidth = 2.dp,
                                                color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                                            )
                                        } else {
                                            androidx.compose.material3.IconButton(
                                                onClick = { onSongMenu(song) },
                                                modifier = Modifier.size(48.dp)
                                            ) {
                                                Text(
                                                    "⋮",
                                                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        repeat(quickPickPages) { idx ->
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 3.dp)
                                    .size(if (idx == quickPagerState.currentPage) 8.dp else 6.dp)
                                    .background(
                                        if (idx == quickPagerState.currentPage)
                                            androidx.compose.material3.MaterialTheme.colorScheme.primary
                                        else
                                            androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                        androidx.compose.foundation.shape.CircleShape
                                    )
                            )
                        }
                    }
                }
            }

            item {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Button(
                        onClick = onDiscoverUnderground,
                        enabled = !undergroundLoading,
                        modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                    ) {
                        if (undergroundLoading) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("Digging…")
                        } else {
                            Text("🎧  Discover Underground")
                        }
                    }
                }
            }

            item {
                if (signedIn && friendsNowPlaying.isNotEmpty()) {
                    FriendsStrip(
                        items = friendsNowPlaying,
                        friends = friendsList,
                        onClick = { onFriendClick(it) }
                    )
                }
            }

            item {
                if (newDrops.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "New drops",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge.copy(
                                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                            ),
                            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (newDropsLoading) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        }
                        androidx.compose.material3.TextButton(
                            onClick = { onSeeAllDrops() }
                        ) { 
                            Text(
                                "See all",
                                style = androidx.compose.material3.MaterialTheme.typography.labelLarge
                            ) 
                        }
                    }
                    HorizontalCarousel(
                        items = newDrops.take(20),
                        key = { it.videoId }
                    ) { drop ->
                        NewDropCard(drop) {
                            onPlayFromQueue(listOf(QueueItem(drop.videoId, drop.title, drop.artist)), 0)
                        }
                    }
                }
            }

            item {
                Text(
                    "Speed Dial",
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge.copy(
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ),
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
                )
                val dialPagerState = androidx.compose.foundation.pager.rememberPagerState(
                    pageCount = { 5 }
                )
                androidx.compose.foundation.pager.HorizontalPager(
                    state = dialPagerState,
                    modifier = Modifier.fillMaxWidth()
                ) { page ->
                    val startPos = page * 9
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    ) {
                        for (row in 0 until 3) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                for (col in 0 until 3) {
                                    val position = startPos + row * 3 + col
                                    val slot = speedDialSlots.firstOrNull { it.position == position }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .aspectRatio(1f)
                                            .padding(6.dp)
                                    ) {
                                        SpeedDialTile(
                                            slot = slot,
                                            onClick = { onSpeedDialTap(slot, position) },
                                            onLongClick = { onSpeedDialLongPress(position) },
                                            showDownloaded = slot?.type == "song" &&
                                                slot.refId != null &&
                                                downloadIds.contains(slot.refId)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    repeat(5) { idx ->
                        Box(
                            modifier = Modifier
                                .padding(horizontal = 3.dp)
                                .size(if (idx == dialPagerState.currentPage) 8.dp else 6.dp)
                                .background(
                                    if (idx == dialPagerState.currentPage)
                                        androidx.compose.material3.MaterialTheme.colorScheme.primary
                                    else
                                        androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    androidx.compose.foundation.shape.CircleShape
                                )
                        )
                    }
                }
            }

            item {
                HorizontalCarousel(
                    title = "Recently played",
                    songs = forYouRecent,
                    onSongClick = { song ->
                        onPlayFromQueue(
                            forYouRecent.map { QueueItem(it.videoId, it.title, it.artist) },
                            forYouRecent.indexOf(song)
                        )
                    }
                )
            }
            item {
                if (topArtistName != null && forYouByArtist.isNotEmpty()) {
                    HorizontalCarousel(
                        title = "More from $topArtistName",
                        songs = forYouByArtist,
                        onSongClick = { song ->
                            onPlayFromQueue(
                                forYouByArtist.map { QueueItem(it.videoId, it.title, it.artist) },
                                forYouByArtist.indexOf(song)
                            )
                        },
                        actionLabel = if (isFollowingTopArtist) "Following" else "Follow",
                        onAction = onToggleFollowTopArtist
                    )
                }
            }
            item {
                HorizontalCarousel(
                    title = "Your favorites",
                    songs = forYouFavorites,
                    onSongClick = { song ->
                        onPlayFromQueue(
                            forYouFavorites.map { QueueItem(it.videoId, it.title, it.artist) },
                            forYouFavorites.indexOf(song)
                        )
                    }
                )
            }

            item { Spacer(Modifier.height(16.dp)) }
        }
}
