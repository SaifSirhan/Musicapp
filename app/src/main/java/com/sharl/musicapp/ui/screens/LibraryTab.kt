package com.sharl.musicapp.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.Downloader
import com.sharl.musicapp.DownloadState
import com.sharl.musicapp.QueueItem
import com.sharl.musicapp.data.Download
import com.sharl.musicapp.data.LocalMedia
import com.sharl.musicapp.data.NowPlaying
import com.sharl.musicapp.data.Playlist
import com.sharl.musicapp.data.PlaylistRepository
import com.sharl.musicapp.data.Profile
import com.sharl.musicapp.data.SharedPlaylistSong
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.data.SongDao
import com.sharl.musicapp.ui.components.SongRow
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.util.youtubeThumb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// Library tab (tab 2), extracted verbatim from PlayerScreen. All captured state
// arrives as parameters — no ViewModel, per the Phase 16 constraints. It is a
// ColumnScope extension because the root Column consumes weight(1f) from the
// PlayerScreen column.
@OptIn(ExperimentalMaterial3Api::class)
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
    onDownloadMenuAction: (Download, DownloadMenuAction) -> Unit = { _, _ -> },
    playlists: List<Playlist>,
    openPlaylistId: Long?,
    onOpenPlaylistChange: (Long?) -> Unit,
    playlistRepo: PlaylistRepository,
    onCreatePlaylist: () -> Unit,
    onRequestRename: (String) -> Unit,
    onRequestDelete: () -> Unit,
    onPlaylistSongMenu: (Song) -> Unit,
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
    localMedia: List<LocalMedia>,
    hasMediaPermission: Boolean,
    onPlayLocal: (List<LocalMedia>, Int) -> Unit,
    onRequestMediaPermission: () -> Unit,
    onRescanLocal: () -> Unit,
    songDao: SongDao, // Phase 44d: For artist queries
) {
    // Phase 44d: Nested tabs for Songs/Artists/Playlists
    var librarySubTab by remember { mutableStateOf(0) } // 0=Songs, 1=Artists, 2=Playlists
    var selectedArtist by remember { mutableStateOf<String?>(null) }
    
    // Phase 44d: Get unique artists from downloads
    val artists = remember(downloads) {
        downloads.groupBy { it.artist }
            .filterKeys { it.isNotBlank() }
            .map { (artistName, songs) -> 
                artistName to (songs.first().thumbnailUrl ?: youtubeThumb(songs.first().videoId))
            }
            .sortedBy { it.first.lowercase() }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
    ) {
        // Phase 44d: Top-level TabRow for Songs/Artists/Playlists
        androidx.compose.material3.TabRow(
            selectedTabIndex = librarySubTab,
            modifier = Modifier.fillMaxWidth()
        ) {
            androidx.compose.material3.Tab(
                selected = librarySubTab == 0,
                onClick = { librarySubTab = 0 },
                text = { Text("Songs") }
            )
            androidx.compose.material3.Tab(
                selected = librarySubTab == 1,
                onClick = { librarySubTab = 1 },
                text = { Text("Artists") }
            )
            androidx.compose.material3.Tab(
                selected = librarySubTab == 2,
                onClick = { librarySubTab = 2 },
                text = { Text("Playlists") }
            )
        }

        when (librarySubTab) {
            0 -> {
                // Songs Tab - show DownloadsScreen with search
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    DownloadsScreen(
                        downloads = downloads,
                        downloadStates = downloadStates,
                        onPlay = { index ->
                            onPlayFromQueue(
                                downloads.map { QueueItem(it.videoId, it.title, it.artist) },
                                index
                            )
                        },
                        onRemove = { d ->
                            scope.launch { Downloader.remove(context, d) }
                        },
                        onRemoveAll = { onRemoveAllDownloads() },
                        onFindMusic = { onFindMusic() },
                        onMenuAction = onDownloadMenuAction
                    )
                }
            }
            1 -> {
                // Artists Tab - grid of unique artists
                if (selectedArtist != null) {
                    // Show ArtistDetailScreen when artist is selected
                    ArtistDetailScreen(
                        artistName = selectedArtist!!,
                        songDao = songDao,
                        onBack = { selectedArtist = null },
                        onPlayAll = { songs ->
                            onPlayFromQueue(
                                songs.map { QueueItem(it.videoId, it.title, it.artist) },
                                0
                            )
                        },
                        onSongClick = { song ->
                            onPlayFromQueue(
                                listOf(QueueItem(song.videoId, song.title, song.artist)),
                                0
                            )
                        },
                        onSongMenu = onSongMenu
                    )
                } else {
                    // Show artists grid
                    androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                        columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
                    ) {
                        items(artists, key = { it.first }) { (artistName, thumbnailUrl) ->
                            androidx.compose.foundation.layout.Column(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .clickable { selectedArtist = artistName }
                                    .padding(8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Thumbnail(thumbnailUrl, 80.dp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    artistName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        
                        if (artists.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "No artists found. Download some songs first.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                // Playlists Tab
                Column(modifier = Modifier.fillMaxSize()) {
                    // Phase 45b: Import button in TopAppBar
                    androidx.compose.material3.TopAppBar(
                        title = { Text("Playlists") },
                        actions = {
                            androidx.compose.material3.IconButton(
                                onClick = onImportClick
                            ) {
                                Text("⤓", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    )
                    
                    Box(
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
                                onPlayFromQueue(songs.map { QueueItem(it.videoId, it.title, it.artist) }, index)
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
                }
            }
        }
    }
}
