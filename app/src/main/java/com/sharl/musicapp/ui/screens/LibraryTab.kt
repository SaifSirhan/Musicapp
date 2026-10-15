package com.sharl.musicapp.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.Downloader
import com.sharl.musicapp.DownloadState
import com.sharl.musicapp.QueueItem
import com.sharl.musicapp.data.Download
import com.sharl.musicapp.data.NowPlaying
import com.sharl.musicapp.data.Playlist
import com.sharl.musicapp.data.PlaylistRepository
import com.sharl.musicapp.data.PlaylistSong
import com.sharl.musicapp.data.Profile
import com.sharl.musicapp.data.SharedPlaylistSong
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.ui.components.SongRow
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.util.youtubeThumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Library tab (tab 2), extracted verbatim from PlayerScreen. All captured state
// arrives as parameters — no ViewModel, per the Phase 16 constraints. It is a
// ColumnScope extension because the root Column consumes weight(1f) from the
// PlayerScreen column.
@Composable
fun ColumnScope.LibraryTab(
    context: Context,
    scope: CoroutineScope,
    libraryTab: Int,
    onLibraryTabChange: (Int) -> Unit,
    libraryLayout: Int,
    onLibraryLayoutChange: (Int) -> Unit,
    onImportClick: () -> Unit,
    downloads: List<Download>,
    downloadStates: Map<String, DownloadState>,
    onRemoveAllDownloads: () -> Unit,
    onFindMusic: () -> Unit,
    playlists: List<Playlist>,
    openPlaylistId: Long?,
    onOpenPlaylistChange: (Long?) -> Unit,
    playlistRepo: PlaylistRepository,
    onCreatePlaylist: () -> Unit,
    onRequestRename: (String) -> Unit,
    onRequestDelete: () -> Unit,
    onPlaylistSongMenu: (PlaylistSong) -> Unit,
    onPlaylistMenu: (Playlist) -> Unit,
    favorites: List<Song>,
    onToggleFavorite: (Song) -> Unit,
    onSongMenu: (Song) -> Unit,
    downloadIds: List<String>,
    loadingVideoId: String?,
    onPreExtract: suspend (String) -> Unit,
    onPlayFromQueue: (List<QueueItem>, Int) -> Unit,
    friendsConfigured: Boolean,
    signedIn: Boolean,
    friends: List<Profile>,
    friendsLoading: Boolean,
    friendsError: String?,
    selectedFriend: Profile?,
    selectedFriendSongs: List<SharedPlaylistSong>,
    selectedFriendSongsLoading: Boolean,
    friendsNowPlaying: List<NowPlaying>,
    onOpenFriend: (Profile) -> Unit,
    onFriendBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Only the chip strip scrolls; the trailing icons stay anchored
            // to the end so they're always reachable on narrow screens.
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = libraryTab == 0,
                    onClick = { onLibraryTabChange(0) },
                    label = { Text("Downloads", maxLines = 1, softWrap = false) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = libraryTab == 1,
                    onClick = { onLibraryTabChange(1) },
                    label = { Text("Playlists", maxLines = 1, softWrap = false) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = libraryTab == 2,
                    onClick = { onLibraryTabChange(2) },
                    label = { Text("Favorites", maxLines = 1, softWrap = false) }
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = libraryTab == 3,
                    onClick = { onLibraryTabChange(3) },
                    label = { Text("Friends", maxLines = 1, softWrap = false) }
                )
            }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = { onImportClick() }) {
                    Text(
                        "⤓",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                IconButton(onClick = { onLibraryLayoutChange(if (libraryLayout == 0) 1 else 0) }) {
                    Text(
                        if (libraryLayout == 0) "▦" else "☰",
                        style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        when (libraryTab) {
            0 -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                DownloadsScreen(
                    downloads = downloads,
                    downloadStates = downloadStates,
                    onPlay = { index ->
                        onPlayFromQueue(
                            downloads.map { QueueItem(it.videoId, it.title) },
                            index
                        )
                    },
                    onRemove = { d ->
                        scope.launch { Downloader.remove(context, d) }
                    },
                    onRemoveAll = { onRemoveAllDownloads() },
                    onFindMusic = { onFindMusic() }
                )
            }
            1 -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                PlaylistsScreen(
                    playlists = playlists,
                    openPlaylistId = openPlaylistId,
                    playlistRepo = playlistRepo,
                    onBack = { onOpenPlaylistChange(null) },
                    onOpenPlaylist = { id -> onOpenPlaylistChange(id) },
                    onPlayFromPlaylist = { songs, index ->
                        onPlayFromQueue(songs.map { QueueItem(it.videoId, it.title) }, index)
                    },
                    onCreatePlaylist = { onCreatePlaylist() },
                    playlistName = playlists.firstOrNull { it.id == openPlaylistId }?.name ?: "",
                    playlistCreatedAt = playlists.firstOrNull { it.id == openPlaylistId }?.createdAt ?: 0L,
                    onRequestRename = {
                        onRequestRename(playlists.firstOrNull { it.id == openPlaylistId }?.name ?: "")
                    },
                    onRequestDelete = { onRequestDelete() },
                    onSongMenu = { ps -> onPlaylistSongMenu(ps) },
                    onPlaylistMenu = { pl -> onPlaylistMenu(pl) },
                    loadingVideoId = loadingVideoId,
                    onPreExtract = { id -> scope.launch { onPreExtract(id) } }
                )
            }
            2 -> {
                if (favorites.isEmpty()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "No favorites yet — tap ☆ on a song to add one.",
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                } else if (libraryLayout == 0) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(top = 8.dp)
                    ) {
                        val items = favorites.map { QueueItem(it.videoId, it.title) }
                        itemsIndexed(favorites, key = { _, song -> song.videoId }) { idx, song ->
                            SongRow(
                                song = song,
                                onClick = { onPlayFromQueue(items, idx) },
                                onToggleFavorite = { onToggleFavorite(song) },
                                onLongClick = { onSongMenu(song) },
                                showDownloaded = downloadIds.contains(song.videoId),
                                isLoading = song.videoId == loadingVideoId
                            )
                        }
                    }
                } else {
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp)
                    ) {
                        items(favorites, key = { it.videoId }) { song ->
                            Column(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .clickable {
                                        onPlayFromQueue(
                                            favorites.map { QueueItem(it.videoId, it.title) },
                                            favorites.indexOf(song)
                                        )
                                    }
                            ) {
                                Thumbnail(
                                    song.thumbnailUrl ?: youtubeThumb(song.videoId),
                                    160.dp
                                )
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    song.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    song.artist,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
            3 -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                FriendsScreen(
                    configured = friendsConfigured,
                    signedIn = signedIn,
                    friends = friends,
                    loading = friendsLoading,
                    error = friendsError,
                    selectedFriend = selectedFriend,
                    friendSongs = selectedFriendSongs,
                    friendSongsLoading = selectedFriendSongsLoading,
                    nowPlaying = friendsNowPlaying,
                    onBack = { onFriendBack() },
                    onBackToList = { onFriendBack() },
                    onOpenFriend = { f -> onOpenFriend(f) },
                    onPlayFriendSongs = { idx ->
                        onPlayFromQueue(
                            selectedFriendSongs.map { QueueItem(it.videoId, it.title ?: "") },
                            idx
                        )
                    },
                    onSignIn = { onSignIn() }
                )
            }
        }
    }
}
