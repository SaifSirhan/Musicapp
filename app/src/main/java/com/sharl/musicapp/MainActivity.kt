package com.sharl.musicapp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.sharl.musicapp.data.AppDatabase
import com.sharl.musicapp.data.AuthRepository
import com.sharl.musicapp.data.DiagnosticsManager
import com.sharl.musicapp.data.Download
import com.sharl.musicapp.data.DownloadRepository
import com.sharl.musicapp.data.FollowRepository
import com.sharl.musicapp.data.FriendsRepository
import com.sharl.musicapp.data.ImportRepository
import com.sharl.musicapp.data.LibraryReconciler
import com.sharl.musicapp.data.LocalMedia
import com.sharl.musicapp.data.LocalMediaRepository
import com.sharl.musicapp.data.MixRepository
import com.sharl.musicapp.data.ResolutionStats
import com.sharl.musicapp.data.StreamingBlockedException
import com.sharl.musicapp.data.OfflineException
import com.sharl.musicapp.data.NewDropsRepository
import com.sharl.musicapp.data.NowPlaying
import com.sharl.musicapp.data.PlaybackCache
import com.sharl.musicapp.data.Playlist
import com.sharl.musicapp.data.PlaylistRepository
import com.sharl.musicapp.data.Profile
import com.sharl.musicapp.data.ProfileRepository
import com.sharl.musicapp.data.SettingsRepo
import com.sharl.musicapp.data.SharedPlaylistSong
import com.sharl.musicapp.data.Song
import com.sharl.musicapp.data.SongRepository
import com.sharl.musicapp.data.SpeedDialRepository
import com.sharl.musicapp.data.SpeedDialSlot
import com.sharl.musicapp.data.SpotifyImporter
import com.sharl.musicapp.data.UpdateRepository
import com.sharl.musicapp.data.UpdateState
import com.sharl.musicapp.data.YtmImporter
import java.io.File
import com.sharl.musicapp.ui.components.MenuItem
import com.sharl.musicapp.ui.components.SongActionsSheet
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.components.asSong
import com.sharl.musicapp.ui.screens.ArtistScreen
import com.sharl.musicapp.ui.screens.DownloadMenuAction
import com.sharl.musicapp.ui.screens.ForYouScreen
import com.sharl.musicapp.ui.screens.HomeTab
import com.sharl.musicapp.ui.screens.ImportAccountScreen
import com.sharl.musicapp.ui.screens.ImportScreen
import com.sharl.musicapp.ui.screens.LibraryTab
import com.sharl.musicapp.ui.screens.NewDropsScreen
import com.sharl.musicapp.ui.screens.NowPlayingScreen
import com.sharl.musicapp.ui.screens.PlaylistsScreen
import com.sharl.musicapp.ui.screens.PrivacyPolicyScreen
import com.sharl.musicapp.ui.screens.SearchTab
import com.sharl.musicapp.ui.screens.SettingsScreen
import com.sharl.musicapp.ui.screens.SignInScreen
import com.sharl.musicapp.ui.screens.SongPickerScreen
import com.sharl.musicapp.ui.screens.VideoPlayerScreen
import com.sharl.musicapp.ui.theme.AccentOptions
import com.sharl.musicapp.ui.theme.DefaultAccent
import com.sharl.musicapp.ui.theme.MusicAppTheme
import com.sharl.musicapp.ui.util.formatRelative
import com.sharl.musicapp.ui.util.youtubeThumb
import kotlinx.coroutines.launch


// The failing cause, not the recovery wrapper: ExoPlayer's HttpDataSource already
// includes the CDN's HTTP status ("...502: Response code: 403").
private fun PlaybackException.diagnostic(): String {
    val detail = message.orEmpty().take(140).ifBlank { "no detail" }
    return "code $errorCode · $detail"
}

class MainActivity : ComponentActivity() {
    // Set when the Spotify redirect arrives before the UI exists (cold start),
    // so PlayerScreen can open the import screen once it composes.
    private var pendingImportRedirect = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        SpotifyImporter.init(applicationContext)
        handleSpotifyRedirect(intent)
        setContent {
            val context = LocalContext.current
            val prefs = remember {
                context.getSharedPreferences("theme", Context.MODE_PRIVATE)
            }
            var accentIndex by remember {
                mutableStateOf(prefs.getInt("accent_index", 0))
            }
            val openImport = pendingImportRedirect
            pendingImportRedirect = false
            val accent = AccentOptions.getOrNull(accentIndex)?.color ?: DefaultAccent
            val snackbarHostState = remember { SnackbarHostState() }

            MusicAppTheme(accent = accent) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    PlayerScreen(
                        modifier = Modifier.padding(innerPadding),
                        accentIndex = accentIndex,
                        onAccentChange = { idx ->
                            accentIndex = idx
                            prefs.edit().putInt("accent_index", idx).apply()
                        },
                        snackbarHostState = snackbarHostState,
                        openImportOnStart = openImport
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSpotifyRedirect(intent)
    }

    private fun handleSpotifyRedirect(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "com.sharl.musicapp" && data.host == "callback") {
            pendingImportRedirect = true
            // Only Spotify uses browser redirects now; YouTube uses device flow.
            SpotifyImporter.handleRedirect(applicationContext, data)
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    modifier: Modifier = Modifier,
    accentIndex: Int,
    onAccentChange: (Int) -> Unit,
    snackbarHostState: SnackbarHostState = SnackbarHostState(),
    openImportOnStart: Boolean = false
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { SongRepository(context) }
    val albumDownloads = remember { com.sharl.musicapp.data.AlbumDownloadRepository(context.applicationContext) }
    val recent by repo.recentlyPlayed().collectAsState(initial = emptyList())
    val favorites by repo.favorites().collectAsState(initial = emptyList())
    val forYouRecent by repo.recentSongs(10).collectAsState(initial = emptyList())
    val forYouTop by repo.mostPlayed(10).collectAsState(initial = emptyList())
    val forYouFavorites by repo.favoriteSongs(10).collectAsState(initial = emptyList())
    val allSongs by repo.allSongs().collectAsState(initial = emptyList())
    val topArtistName by repo.topArtist().collectAsState(initial = null)
    val forYouByArtist by (topArtistName?.let {
        repo.songsByArtist(it, 10)
    } ?: kotlinx.coroutines.flow.flowOf(emptyList())).collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf(0) }  // 0 = Home, 1 = Search, 2 = Library, 3 = For You
    var libraryTab by remember { mutableStateOf(0) }  // 0 = Downloads, 1 = Playlists, 2 = Favorites
    var libraryLayout by remember { mutableStateOf(0) }  // 0 = List, 1 = Grid
    var showColorPicker by remember { mutableStateOf(false) }
    var menuSong by remember { mutableStateOf<Song?>(null) }
    var queueMenuItem by remember { mutableStateOf<QueueItem?>(null) }

    var status by remember { mutableStateOf("Connecting...") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var localMatches by remember { mutableStateOf<List<Song>>(emptyList()) }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var connected by remember { mutableStateOf(false) }

    val queueController = remember(controller) { controller?.let { QueueController(it) } }
    val emptyQueue = remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList<QueueItem>()) }
    val emptyUid = remember { kotlinx.coroutines.flow.MutableStateFlow<String?>(null) }
    val queue by (queueController?.queue ?: emptyQueue).collectAsState()
    val currentUid by (queueController?.currentUid ?: emptyUid).collectAsState()
    val queueIndex = queue.indexOfFirst { it.uid == currentUid }
    DisposableEffect(queueController) {
        onDispose { queueController?.close() }
    }
    LaunchedEffect(queueController) {
        queueController?.errors?.collect { message ->
            if (message != null) {
                status = message
                snackbarHostState.showSnackbar(message)
            }
        }
    }
    var nowPlayingTitle by remember { mutableStateOf<String?>(null) }
    var nowPlayingArtist by remember { mutableStateOf<String?>(null) }
    var nowPlayingThumbnail by remember { mutableStateOf<String?>(null) }
    var isPlaying by remember { mutableStateOf(false) }

    var showNowPlaying by remember { mutableStateOf(false) }
    var isShuffled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(0) }  // 0=off, 1=all, 2=one

    var lyrics by remember { mutableStateOf<List<LyricLine>>(emptyList()) }
    var lyricsLoading by remember { mutableStateOf(false) }
    var nowPlayingTab by remember { mutableStateOf(0) }  // inside Now Playing: 0 = Art, 1 = Lyrics, 2 = Queue
    var currentPosMs by remember { mutableStateOf(0L) }

    var currentDurationMs by remember { mutableStateOf(0L) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableStateOf(0L) }
    var isVideoMode by remember { mutableStateOf(false) }
    var videoModeLoading by remember { mutableStateOf(false) }

    var loadingVideoId by remember { mutableStateOf<String?>(null) }
    var innerTubeFailCount by remember { mutableStateOf(ResolutionStats.innerTubeFailCount(context)) }
    var fastEngine by remember { mutableStateOf(ResolutionStats.lastWasFast(context)) }
    val settings = remember { SettingsRepo(context) }

    LaunchedEffect(Unit) {
        // Phase 50: rows written before normalization existed get a title key
        // and a duration (from downloads) without blocking the UI.
        withContext(Dispatchers.IO) {
            repo.backfillNormalizedTitles()
            try {
                LibraryReconciler.forContext(context).reconcile()
                Downloader.recoverCompleted(context, settings)
                Downloader.resumePending(context, settings)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Log.w("LIBRARY", "Library recovery could not be completed")
            }
        }
    }

    val playlistRepo = remember { PlaylistRepository(context) }
    val playlists by playlistRepo.all().collectAsState(initial = emptyList())
    var showPlaylists by remember { mutableStateOf(false) }
    var openPlaylistId by remember { mutableStateOf<Long?>(null) }
    var showAddToPlaylist by remember { mutableStateOf<Song?>(null) }
    var showNewPlaylistDialog by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }
    var showRenamePlaylist by remember { mutableStateOf(false) }
    var renamePlaylistName by remember { mutableStateOf("") }
    var showDeletePlaylistConfirm by remember { mutableStateOf(false) }
    var playlistMenuSong by remember { mutableStateOf<Song?>(null) }
    var playlistMenuPlaylistId by remember { mutableStateOf<Long?>(null) }
    // Phase 24: artist page shown over the tabs; null = hidden.
    var artistScreenName by remember { mutableStateOf<String?>(null) }

    val speedDialRepo = remember { SpeedDialRepository(context) }
    val speedDialSlots by speedDialRepo.allSlots().collectAsState(initial = emptyList())
    var speedDialMenuPosition by remember { mutableStateOf<Int?>(null) }
    var showSongPickerForSlot by remember { mutableStateOf<Int?>(null) }

    val mixRepo = remember { MixRepository(context) }
    var mixGeneration by remember { mutableStateOf(0L) }
    // Seed title of the radio queue currently loaded, null for normal playback.
    var activeMixTitle by remember { mutableStateOf<String?>(null) }
    val followRepo = remember { FollowRepository(context) }
    var followedArtists by remember { mutableStateOf(followRepo.artists()) }

    val downloadRepo = remember { DownloadRepository(context) }
    val downloads by downloadRepo.all().collectAsState(initial = emptyList())
    val downloadIds by downloadRepo.allIds().collectAsState(initial = emptyList())
    val localMediaRepo = remember { LocalMediaRepository(context) }
    val localMedia by localMediaRepo.all().collectAsState(initial = emptyList())
    var mediaPermissionGranted by remember { mutableStateOf(false) }
    val downloadStates by Downloader.states.collectAsState()
    var storageExternalState by remember { mutableStateOf(settings.storageExternal) }
    var skipSecondsState by remember { mutableStateOf(settings.skipSeconds) }
    var playbackSpeedState by remember { mutableStateOf(settings.playbackSpeed) }
    var crossfadeSecondsState by remember { mutableStateOf(settings.crossfadeSeconds) }
    var normalizeVolumeState by remember { mutableStateOf(settings.normalizeVolume) }
    var undergroundLoading by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showImportAccount by remember { mutableStateOf(openImportOnStart) }
    var playlistListMenu by remember { mutableStateOf<Playlist?>(null) }
    var playlistListSongCount by remember { mutableStateOf(0) }
    var playlistListDownloaded by remember { mutableStateOf(0) }
    var showRemoveAllDownloads by remember { mutableStateOf(false) }
    var showClearLibraryConfirm by remember { mutableStateOf(false) }
    var showPrivacyPolicy by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var notifyUpdates by remember { mutableStateOf(settings.notifyUpdates) }
    var removalProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // --- In-app updater (Phase 15c). Never auto-downloads. ---
    val updateRepo = remember { UpdateRepository(context) }
    val updateState by updateRepo.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!BuildConfig.DEBUG) updateRepo.check()
    }
    androidx.compose.runtime.LaunchedEffect(updateState) {
        val s = updateState
        if (s is UpdateState.Available &&
            s.info.versionCode != settings.dismissUpdateForVersion &&
            settings.notifyUpdates
        ) {
            val result = snackbarHostState.showSnackbar(
                message = "Update available — tap to install",
                actionLabel = "Install"
            )
            if (result == SnackbarResult.ActionPerformed) {
                showSettings = true
            } else {
                settings.dismissUpdateForVersion = s.info.versionCode
            }
        }
    }

    val newDropsRepo = remember { NewDropsRepository(context) }
    val newDrops by newDropsRepo.drops.collectAsState()
    val newDropsLoading by newDropsRepo.loading.collectAsState()
    var showNewDropsAll by remember { mutableStateOf(false) }

    // seed artists: followed ∪ top-played (SongDao) ∪ favorites' artists
    val newDropsSeedArtists by remember {
        androidx.compose.runtime.derivedStateOf {
            val top = forYouTop.sortedByDescending { it.playCount }.take(10).map { it.artist }
            val favs = favorites.map { it.artist }
            (followedArtists + top + favs).map { it.trim() }.filter { it.isNotEmpty() }
                .distinctBy { it.lowercase() }.take(20)
        }
    }

    // Keyed on the seed list (not Unit) so a cold start still fires once Room's
    // flows have emitted the played/favorite artists.
    androidx.compose.runtime.LaunchedEffect(newDropsSeedArtists, newDropsLoading) {
        if (newDropsSeedArtists.isNotEmpty() && newDropsRepo.isStale() && !newDropsLoading) {
            newDropsRepo.refresh(context, newDropsSeedArtists, followedArtists)
        }
    }

    val importRepo = remember { ImportRepository(context) }
    val importState by importRepo.state.collectAsState()
    var showImport by remember { mutableStateOf(false) }
    var importJob by remember { mutableStateOf<Job?>(null) }

    // --- Sync with friends (Phase 14). Local-only when signed out. ---
    val authRepo = remember { AuthRepository() }
    val profileRepo = remember { ProfileRepository(authRepo) }
    val friendsRepo = remember { FriendsRepository(authRepo) }
    val session by authRepo.session.collectAsState(initial = null)
    val signedIn = session != null
    var showSignIn by remember { mutableStateOf(false) }
    var myProfile by remember { mutableStateOf<Profile?>(null) }
    var friendsList by remember { mutableStateOf<List<Profile>>(emptyList()) }
    var friendsLoading by remember { mutableStateOf(false) }
    var friendsError by remember { mutableStateOf<String?>(null) }
    var addFriendCode by remember { mutableStateOf("") }
    var addFriendStatus by remember { mutableStateOf<String?>(null) }
    var selectedFriend by remember { mutableStateOf<Profile?>(null) }
    var selectedFriendSongs by remember { mutableStateOf<List<SharedPlaylistSong>>(emptyList()) }
    var selectedFriendSongsLoading by remember { mutableStateOf(false) }
    var friendsNowPlaying by remember { mutableStateOf<List<NowPlaying>>(emptyList()) }
    var nowPlayingFriend by remember { mutableStateOf<NowPlaying?>(null) }
    var playlistListIsShared by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(signedIn) {
        if (signedIn) {
            friendsLoading = true
            friendsError = null
            runCatching { profileRepo.ensureProfile() }
                .onFailure { friendsError = it.message }
                .getOrNull()?.let { myProfile = it }
            runCatching { profileRepo.friends() }
                .onSuccess { friendsList = it }
                .onFailure { friendsError = it.message }
            friendsLoading = false
        } else {
            myProfile = null
            friendsList = emptyList()
            friendsNowPlaying = emptyList()
            selectedFriend = null
        }
    }

    // Realtime subscription for friends' now-playing. One channel for the whole
    // screen (the Home strip and Friends screen both read this state).
    androidx.compose.runtime.LaunchedEffect(signedIn, friendsList) {
        if (signedIn && friendsList.isNotEmpty()) {
            friendsRepo.observeFriendsNowPlaying(friendsList.map { it.id })
                .collect { friendsNowPlaying = it }
        } else {
            friendsNowPlaying = emptyList()
        }
    }

    // Debounced now-playing push. Uses the queue (not playFromQueue) so that
    // helper is untouched.
    androidx.compose.runtime.LaunchedEffect(signedIn, queueIndex, nowPlayingTitle) {
        if (signedIn && nowPlayingTitle != null) {
            kotlinx.coroutines.delay(5000)
            val vid = queue.getOrNull(queueIndex)?.videoId
            runCatching { friendsRepo.updateNowPlaying(vid, nowPlayingTitle, nowPlayingArtist) }
        }
    }

    androidx.compose.runtime.LaunchedEffect(forYouRecent) {
        if (forYouRecent.isNotEmpty()) {
            speedDialRepo.seedIfEmpty(forYouRecent)
        }
    }

    androidx.compose.runtime.LaunchedEffect(playlistListMenu, downloadIds) {
        val pl = playlistListMenu
        if (pl != null) {
            val songs = playlistRepo.songs(pl.id).first()
            playlistListSongCount = songs.size
            playlistListDownloaded = songs.count { downloadIds.contains(it.videoId) }
            playlistListIsShared = if (signedIn && authRepo.configured) {
                runCatching { friendsRepo.mySharedPlaylist()?.playlistName == pl.name }
                    .getOrDefault(false)
            } else false
        }
    }

    fun startDownload(
        videoId: String,
        title: String,
        artist: String,
        thumb: String?,
        durationMs: Long
    ) {
        scope.launch {
            try {
                Downloader.download(context, settings, videoId, title, artist, thumb, durationMs)
            } catch (e: Exception) {
                status = "Download failed: ${e.message}"
            }
        }
    }

    // Phase 47: find the artist's albums and download every track we can.
    fun downloadDiscography(artist: String) {
        if (artist.isBlank()) return
        scope.launch {
            status = "Looking up $artist's albums..."
            var downloaded = 0
            var failed = 0
            try {
                val albums = YtdlpHelper.searchAlbums(context, artist, limit = 8)
                if (albums.isEmpty()) {
                    status = "No albums found for $artist"
                    return@launch
                }
                albums.forEachIndexed { albumIdx, album ->
                    status = "Discography ${albumIdx + 1}/${albums.size}: ${album.title}"
                    val tracks = YtdlpHelper.searchAlbumTracks(
                        context, artist,
                        album.videoId.takeIf { com.sharl.musicapp.data.album.AlbumInput.isId(it) } ?: album.title,
                        limit = 100
                    )
                    tracks.forEach { track ->
                        Downloader.enqueue(context, settings, com.sharl.musicapp.data.download.DownloadRequest(
                            track.videoId, track.title, track.channel.orEmpty(), youtubeThumb(track.videoId), track.durationMs ?: 0L
                        ))
                    }
                    tracks.forEachIndexed { trackIdx, t ->
                        try {
                            val row = albumDownloads.downloadTrack(
                                com.sharl.musicapp.data.download.DownloadRequest(
                                    t.videoId, t.title, t.channel.orEmpty(), youtubeThumb(t.videoId), t.durationMs ?: 0L
                                ), album.title, trackIdx + 1
                            )
                            if (row.videoId == t.videoId) downloaded++
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failed++
                            if (Downloader.stateOf(t.videoId) is DownloadState.WaitingForSpace) {
                                status = "Downloads paused: free storage and retry"
                                return@launch
                            }
                            Log.w("DISC", "Album track download failed")
                        }
                    }
                }
                status = "Discography done: $downloaded downloaded" +
                    if (failed > 0) ", $failed failed" else ""
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("DISC", "Discography download failed")
                status = "Discography download failed"
            }
        }
    }

    fun removeAllDownloads() {        val items = downloads
        scope.launch {
            val showProgress = items.size > 5
            items.forEachIndexed { idx, d ->
                if (showProgress) removalProgress = (idx + 1) to items.size
                try {
                    Downloader.remove(context, d)
                } catch (e: Exception) {
                    Log.w("DOWNLOAD", "remove failed for ${d.videoId}", e)
                }
            }
            removalProgress = null
            snackbarHostState.showSnackbar("Downloads removed.")
        }
    }

    fun clearLibrary() {
        scope.launch {
            val db = AppDatabase.get(context)
            db.songDao().clearAll()
            db.playlistDao().clearAll()
            db.speedDialDao().clearAll()
            snackbarHostState.showSnackbar("Library cleared.")
        }
    }

    DisposableEffect(context) {
        Log.d("CTRL", "Connecting to PlaybackService...")
        val token = SessionToken(
            context,
            ComponentName(context, PlaybackService::class.java)
        )
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            try {
                controller = future.get()
                connected = true
                status = "Ready"
                Log.d("CTRL", "MediaController connected")
            } catch (e: Exception) {
                status = "Controller error: ${e.message}"
                Log.e("CTRL", "MediaController connection failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            MediaController.releaseFuture(future)
            Log.d("CTRL", "MediaController released")
        }
    }

    // Phase 22: push the persisted speed onto a freshly connected controller.
    // Later changes are applied by the service listening to SettingsRepo.
    LaunchedEffect(controller, playbackSpeedState) {
        runCatching { controller?.setPlaybackSpeed(playbackSpeedState) }
    }

    // Self-healing playback is installed after playFromQueue is declared (local
    // functions are only visible below their definition).

    var notifPermissionGranted by remember { mutableStateOf(false) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        notifPermissionGranted = granted
        if (!granted) {
            status = "Notification permission denied — controls hidden"
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val already = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            notifPermissionGranted = already
            if (!already) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            notifPermissionGranted = true  // not required pre-33
        }
    }

    // Local media (Phase 20). API 33+ splits audio/video; older releases use
    // the legacy storage permission. Requests are staggered after the
    // notification dialog: two simultaneous permission prompts can clobber
    // each other on some OEM builds.
    fun mediaPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    fun hasMediaPermissions(): Boolean = mediaPermissions().all {
        ContextCompat.checkSelfPermission(context, it) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    val mediaPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = mediaPermissions().all { result[it] == true }
        mediaPermissionGranted = granted
        if (granted) scope.launch { localMediaRepo.refresh() }
    }

    LaunchedEffect(Unit) {
        mediaPermissionGranted = hasMediaPermissions()
        if (hasMediaPermissions()) {
            localMediaRepo.refresh()
        } else {
            kotlinx.coroutines.delay(1200)
            mediaPermissionLauncher.launch(mediaPermissions())
        }
    }

    LaunchedEffect(libraryTab) {
        if (libraryTab == 4 && hasMediaPermissions()) localMediaRepo.refresh()
    }

    LaunchedEffect(showNowPlaying, nowPlayingTitle) {
        while (showNowPlaying && nowPlayingTitle != null) {
            controller?.let { c ->
                if (!isScrubbing) currentPosMs = c.currentPosition
                val d = c.duration
                currentDurationMs = if (d > 0) d else 0L
            }
            kotlinx.coroutines.delay(250)
        }
    }


    fun presentTrack(q: QueueItem, meta: SongMetadata?) {
        nowPlayingTitle = meta?.title ?: q.title
        nowPlayingArtist = meta?.artist ?: q.artist
        nowPlayingThumbnail = meta?.thumbnailUrl
        isVideoMode = q.isVideo
        status = "Playing: ${nowPlayingTitle}"
        if (q.videoId.startsWith("content://") || q.videoId.startsWith("file://")) {
            lyrics = emptyList()
            lyricsLoading = false
            return
        }
        scope.launch {
            repo.recordPlay(
                q.videoId,
                meta?.title ?: q.title,
                meta?.artist ?: q.artist,
                meta?.album,
                meta?.thumbnailUrl
            )
        }
        lyrics = emptyList()
        lyricsLoading = true
        val fetchTitle = meta?.title ?: q.title
        val fetchArtist = meta?.artist ?: q.artist
        scope.launch {
            val result = LyricsHelper.fetch(fetchArtist, fetchTitle)
            if (queueController?.currentUid?.value == q.uid) {
                lyrics = result ?: emptyList()
                lyricsLoading = false
            }
        }
    }

    fun playFromQueue(
        items: List<QueueItem>,
        index: Int,
        mixTitle: String? = null
    ) {
        val target = items.getOrNull(index) ?: return
        val qc = queueController ?: return
        activeMixTitle = mixTitle
        if (qc.queue.value.any { it.uid == target.uid }) {
            qc.play(target.uid)
        } else {
            qc.replace(items, target.uid)
        }
    }

    LaunchedEffect(currentUid) {
        val item = queue.firstOrNull { it.uid == currentUid }
        if (item == null) {
            nowPlayingTitle = null
            nowPlayingArtist = null
            nowPlayingThumbnail = null
            lyrics = emptyList()
            lyricsLoading = false
            isPlaying = false
            currentPosMs = 0L
            currentDurationMs = 0L
        } else {
            val metadata = controller?.currentMediaItem?.mediaMetadata
            presentTrack(item, SongMetadata(
                title = metadata?.title?.toString() ?: item.title,
                artist = metadata?.artist?.toString() ?: item.artist,
                album = metadata?.albumTitle?.toString(),
                thumbnailUrl = metadata?.artworkUri?.toString()
            ))
        }
    }

    DisposableEffect(controller) {
        val c = controller ?: return@DisposableEffect onDispose {}
        isPlaying = c.isPlaying
        isShuffled = c.shuffleModeEnabled
        repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_ALL -> 1
            Player.REPEAT_MODE_ONE -> 2
            else -> 0
        }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onShuffleModeEnabledChanged(enabled: Boolean) { isShuffled = enabled }
            override fun onRepeatModeChanged(mode: Int) {
                repeatMode = when (mode) {
                    Player.REPEAT_MODE_ALL -> 1
                    Player.REPEAT_MODE_ONE -> 2
                    else -> 0
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                DiagnosticsManager.recordPlayerError(error.errorCode, "Local playback failed")
                scope.launch {
                    snackbarHostState.showSnackbar("Playback failed. Try another song.")
                }
            }
        }
        c.addListener(listener)
        onDispose { c.removeListener(listener) }
    }

    fun playNext() {
        controller?.seekToNextMediaItem()
    }

    fun playPrev() {
        controller?.seekToPreviousMediaItem()
    }

    fun appendToQueue(song: Song) {
        queueController?.append(listOf(QueueItem(song.videoId, song.title, song.artist)))
    }

    fun insertIntoQueueNext(song: Song) {
        queueController?.insertNext(QueueItem(song.videoId, song.title, song.artist))
    }

    // Phase 20: local songs/videos are already playable content URIs; no
    // resolution step, and a video opens straight into full-screen playback.
    fun playLocal(items: List<LocalMedia>, index: Int) {
        if (index !in items.indices) return
        val queueItems = items.map {
            QueueItem(
                videoId = it.id,
                title = it.title,
                artist = it.artist,
                localUri = it.id,
                isVideo = it.isVideo
            )
        }
        playFromQueue(queueItems, index)
        if (items[index].isVideo) showNowPlaying = true
    }

    // Phase 26: silent mix. The seed starts immediately on its own; the radio
    // station is generated in the background and swapped into the queue under
    // the still-playing seed, with no blocking overlay.
    fun startMix(seed: Song) {
        menuSong = null
        val seedItem = QueueItem(seed.videoId, seed.title, seed.artist)
        playFromQueue(listOf(seedItem), 0, mixTitle = seed.title)
        // Guards against two mixes racing to rewrite the same queue.
        val generation = ++mixGeneration
        scope.launch {
            try {
                val mix = mixRepo.generateMix(seed)
                if (generation != mixGeneration) return@launch
                if (mix.size <= 1) {
                    // Keep the seed playing; it already has a one-track queue.
                    Log.d("MIX", "silent mix for ${seed.videoId} returned no neighbours")
                    return@launch
                }
                val qc = queueController ?: return@launch
                if (qc.currentUid.value != seedItem.uid) return@launch
                qc.append(mix.filter { it.videoId != seed.videoId }.map {
                    QueueItem(it.videoId, it.title, it.artist)
                })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never interrupt playback for a failed mix build.
                Log.w("MIX", "silent mix failed for ${seed.videoId}", e)
            }
        }
    }

    fun removeFromQueue(item: QueueItem) {
        queueController?.remove(item.uid)
    }

    fun moveQueueItemNext(item: QueueItem) {
        queueController?.moveNext(item.uid)
    }

    fun clearQueue() {
        queueController?.clear()
        activeMixTitle = null
        mixGeneration++
        showNowPlaying = false
    }

    fun notInterested(song: Song) {
        scope.launch { repo.hide(song) }
        queue.firstOrNull { it.uid == currentUid && it.videoId == song.videoId }
            ?.let { queueController?.remove(it.uid) }
        status = "Not interested: ${song.title}"
    }

    // Phase 42: Removed - depended on InnerTube streaming. Local-first only.
    /*
    fun discoverUnderground() {
        if (undergroundLoading) return
        scope.launch {
            undergroundLoading = true
            status = "Digging for underground…"
            try {
                val picks = InnerTubeHelper.discoverUnderground(limit = 20)
                if (picks.isEmpty()) {
                    status = "No underground picks found"
                    snackbarHostState.showSnackbar(
                        "Couldn't find anything underground — check your connection."
                    )
                    return@launch
                }
                playFromQueue(
                    picks.map { QueueItem(it.videoId, it.title, it.artist) },
                    0,
                    mixTitle = "Underground"
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Discovery failed: ${e.message}"
                snackbarHostState.showSnackbar(
                    "Couldn't reach the underground — check your connection."
                )
            } finally {
                undergroundLoading = false
            }
        }
    }
    */

    fun toggleFollow(artist: String) {
        followRepo.toggle(artist)
        followedArtists = followRepo.artists()
        val seeds = (followedArtists + newDropsSeedArtists)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
        if (seeds.isNotEmpty()) {
            scope.launch { newDropsRepo.refresh(context, seeds, followedArtists) }
        }
    }

    fun onSpeedDialTap(slot: SpeedDialSlot?, position: Int) {        if (slot == null || slot.type == "empty") {
            speedDialMenuPosition = position
            return
        }
        when (slot.type) {
            "song" -> {
                // Discovery source: a pinned song opens a mix around it rather
                // than a one-track queue (Rule 2).
                slot.refId?.let { vid ->
                    scope.launch {
                        val song = runCatching { repo.get(vid) }.getOrNull()
                            ?: Song(
                                videoId = vid,
                                title = slot.title ?: "",
                                artist = "",
                                thumbnailUrl = slot.thumbnailUrl ?: youtubeThumb(vid),
                                lastPlayedAt = 0L,
                                playCount = 0
                            )
                        startMix(song)
                    }
                }
            }
            "playlist" -> {
                val pid = slot.refId?.toLongOrNull() ?: return
                scope.launch {
                    val songs = playlistRepo.songs(pid).first()
                    if (songs.isNotEmpty()) {
                        playFromQueue(songs.map { QueueItem(it.videoId, it.title, it.artist) }, 0)
                    }
                }
            }
            "shuffle_fav" -> {
                val shuffled = favorites.shuffled()
                if (shuffled.isNotEmpty()) {
                    playFromQueue(shuffled.map { QueueItem(it.videoId, it.title, it.artist) }, 0)
                }
            }
            "shuffle_recent" -> {
                val shuffled = forYouRecent.shuffled()
                if (shuffled.isNotEmpty()) {
                    playFromQueue(shuffled.map { QueueItem(it.videoId, it.title, it.artist) }, 0)
                }
            }
        }
    }

    fun toggleVideoMode() {
        val item = queue.firstOrNull { it.uid == currentUid } ?: return
        if (!item.isVideo) {
            status = "This download contains audio only"
            return
        }
        isVideoMode = !isVideoMode
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (artistScreenName != null) {
            androidx.activity.compose.BackHandler(enabled = true) {
                artistScreenName = null
            }
            ArtistScreen(
                artistName = artistScreenName!!,
                localSongs = allSongs.filter {
                    it.artist.equals(artistScreenName, ignoreCase = true)
                },
                context = context,
                scope = scope,
                onPlay = { items, index -> playFromQueue(items, index) },
                onDownload = { videoId, title, artist, album, trackNumber, durationMs ->
                    status = "Downloading to Library..."
                    loadingVideoId = videoId
                    scope.launch {
                        try {
                            val row = albumDownloads.downloadTrack(
                                com.sharl.musicapp.data.download.DownloadRequest(
                                    videoId, title, artist, youtubeThumb(videoId), durationMs
                                ), album, trackNumber
                            )
                            status = if (row.videoId == videoId) "Downloaded: $title" else "Already downloaded: $title"
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            status = if (Downloader.stateOf(videoId) is DownloadState.WaitingForSpace)
                                "Downloads paused: free storage and retry" else "Download failed: $title"
                        } finally {
                            if (loadingVideoId == videoId) loadingVideoId = null
                        }
                    }
                },
                downloadedIds = downloadIds.toSet(),
                onDownloadDiscography = { name -> downloadDiscography(name) },
                onBack = { artistScreenName = null }
            )
        } else if (showPlaylists) {
            androidx.activity.compose.BackHandler {
                if (openPlaylistId != null) openPlaylistId = null else showPlaylists = false
            }
            PlaylistsScreen(
                playlists = playlists,
                openPlaylistId = openPlaylistId,
                playlistRepo = playlistRepo,
                onBack = {
                    if (openPlaylistId != null) openPlaylistId = null else showPlaylists = false
                },
                onOpenPlaylist = { id -> openPlaylistId = id },
                onPlayFromPlaylist = { songs, index ->
                    playFromQueue(songs.map { QueueItem(it.videoId, it.title, it.artist) }, index)
                },
                onCreatePlaylist = { showNewPlaylistDialog = true },
                playlistName = playlists.firstOrNull { it.id == openPlaylistId }?.name ?: "",
                playlistCreatedAt = playlists.firstOrNull { it.id == openPlaylistId }?.createdAt ?: 0L,
                onRequestRename = {
                    renamePlaylistName = playlists.firstOrNull { it.id == openPlaylistId }?.name ?: ""
                    showRenamePlaylist = true
                },
                onRequestDelete = { showDeletePlaylistConfirm = true },
                onSongMenu = { ps ->
                    playlistMenuSong = ps
                    playlistMenuPlaylistId = openPlaylistId
                },
                onPlaylistMenu = { pl -> playlistListMenu = pl },
                loadingVideoId = loadingVideoId,
            )
        } else if (showNowPlaying && nowPlayingTitle != null) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showNowPlaying = false
            }
            val currentTrack = queue.getOrNull(queueIndex)
            val currentVideoId = currentTrack?.videoId
            val currentSong = allSongs.firstOrNull { it.videoId == currentVideoId }
                ?: currentTrack?.let {
                    Song(
                        videoId = it.videoId,
                        title = nowPlayingTitle ?: it.title,
                        artist = nowPlayingArtist ?: it.artist,
                        thumbnailUrl = nowPlayingThumbnail,
                        lastPlayedAt = 0L,
                        playCount = 0
                    )
                }
            NowPlayingScreen(
                title = nowPlayingTitle ?: "",
                artist = nowPlayingArtist ?: "",
                thumbnailUrl = nowPlayingThumbnail,
                isPlaying = isPlaying,
                isLoading = loadingVideoId != null && loadingVideoId == queue.getOrNull(queueIndex)?.videoId,
                isShuffled = isShuffled,
                repeatMode = repeatMode,
                lyrics = lyrics,
                lyricsLoading = lyricsLoading,
                currentPosMs = currentPosMs,
                currentDurationMs = currentDurationMs,
                isScrubbing = isScrubbing,
                scrubPositionMs = scrubPositionMs,
                onScrubStart = { isScrubbing = true; scrubPositionMs = currentPosMs },
                onScrubChange = { scrubPositionMs = it },
                onScrubEnd = {
                    controller?.seekTo(scrubPositionMs)
                    currentPosMs = scrubPositionMs
                    isScrubbing = false
                },
                isVideoMode = isVideoMode,
                videoModeLoading = videoModeLoading,
                onToggleVideoMode = { toggleVideoMode() },
                mediaController = controller,
                nowPlayingTab = nowPlayingTab,
                onNowPlayingTabChange = { nowPlayingTab = it },
                queue = queue,
                queueIndex = queueIndex,
                queueTabLabel = activeMixTitle?.let { "Mix: $it" } ?: "Queue",
                onQueueItemClick = { uid -> queueController?.play(uid) },
                onQueueItemMenu = { item -> queueMenuItem = item },
                onClearQueue = { clearQueue() },
                onClearUpcoming = { queueController?.clearUpcoming() },
                onBack = { showNowPlaying = false },
                onPlayPause = {
                    val c = controller ?: return@NowPlayingScreen
                    if (c.isPlaying) {
                        c.pause()
                        isPlaying = false
                    } else {
                        c.play()
                        isPlaying = true
                    }
                },
                skipSeconds = skipSecondsState,
                onSkipBack = {
                    controller?.let { c ->
                        c.seekTo((c.currentPosition - skipSecondsState * 1000L).coerceAtLeast(0L))
                    }
                },
                onSkipForward = {
                    controller?.let { c ->
                        c.seekTo(c.currentPosition + skipSecondsState * 1000L)
                    }
                },
                onSkipPrevious = { playPrev() },
                onSkipNext = { playNext() },
                playbackSpeed = playbackSpeedState,
                onPlaybackSpeedChange = { v ->
                    playbackSpeedState = v
                    settings.playbackSpeed = v
                    runCatching { controller?.setPlaybackSpeed(v) }
                },
                onNotInterested = {
                    queue.getOrNull(queueIndex)?.let { q ->
                        notInterested(
                            Song(
                                videoId = q.videoId,
                                title = nowPlayingTitle ?: q.title,
                                artist = nowPlayingArtist ?: q.artist,
                                thumbnailUrl = nowPlayingThumbnail,
                                lastPlayedAt = 0L,
                                playCount = 0
                            )
                        )
                    }
                },
                onArtistClick = {
                    nowPlayingArtist?.takeIf { it.isNotBlank() }?.let {
                        artistScreenName = it
                    }
                },
                onShuffleToggle = {
                    isShuffled = !isShuffled
                    controller?.shuffleModeEnabled = isShuffled
                },
                onRepeatToggle = {
                    repeatMode = (repeatMode + 1) % 3
                    controller?.repeatMode = when (repeatMode) {
                        1 -> Player.REPEAT_MODE_ALL
                        2 -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                },
                isFavorite = currentSong?.isFavorite == true,
                downloadedIds = downloadIds.toSet(),
                onDownloadTrack = { q ->
                    startDownload(q.videoId, q.title, q.artist, null, 0L)
                },
                onToggleFavorite = {
                    currentSong?.let { song ->
                        scope.launch { repo.toggleFavorite(song) }
                    }
                },
                onAddToPlaylist = {
                    currentSong?.let { showAddToPlaylist = it }
                },
                onShare = {
                    val vid = currentVideoId
                    if (vid != null) {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "https://www.youtube.com/watch?v=$vid"
                            )
                        }
                        context.startActivity(
                            Intent.createChooser(share, "Share song")
                        )
                    }
                }
            )
        } else if (showPrivacyPolicy) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showPrivacyPolicy = false
            }
            PrivacyPolicyScreen(onBack = { showPrivacyPolicy = false })
        } else if (showSongPickerForSlot != null) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showSongPickerForSlot = null
            }
            val slot = showSongPickerForSlot!!
            SongPickerScreen(
                recent = forYouRecent,
                favorites = favorites,
                allSongs = allSongs,
                onBack = { showSongPickerForSlot = null },
                onPick = { song ->
                    scope.launch {
                        speedDialRepo.assignSong(
                            slot,
                            song.videoId,
                            song.title,
                            song.thumbnailUrl ?: youtubeThumb(song.videoId)
                        )
                    }
                    showSongPickerForSlot = null
                },
                onToggleFavorite = { song -> scope.launch { repo.toggleFavorite(song) } }
            )
        } else if (showSignIn) {
            androidx.activity.compose.BackHandler(enabled = true) { showSignIn = false }
            SignInScreen(
                configured = authRepo.configured,
                externalError = friendsError,
                onSignIn = { idToken, nonce ->
                    friendsError = null
                    scope.launch {
                        runCatching { authRepo.signInWithGoogle(idToken, nonce) }
                            .onSuccess { showSignIn = false }
                            .onFailure { friendsError = it.message ?: "Sign-in failed." }
                    }
                },
                onBack = { showSignIn = false }
            )
        } else if (showSettings) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showSettings = false
            }
            SettingsScreen(
                storageExternal = storageExternalState,
                onStorageChange = { ext ->
                    storageExternalState = ext
                    settings.storageExternal = ext
                },
                downloadDirPath = settings.downloadDir(context).absolutePath,
                downloadCount = downloads.size,
                totalBytes = downloads.sumOf { it.fileSizeBytes },
                onRemoveAll = { showRemoveAllDownloads = true },
                onClearLibrary = { showClearLibraryConfirm = true },
                onViewPrivacyPolicy = { showPrivacyPolicy = true },
                notifyUpdates = notifyUpdates,
                onNotifyUpdatesChange = { v ->
                    notifyUpdates = v
                    settings.notifyUpdates = v
                },
                skipSeconds = skipSecondsState,
                onSkipSecondsChange = { v ->
                    skipSecondsState = v
                    settings.skipSeconds = v
                },
                playbackSpeed = playbackSpeedState,
                onPlaybackSpeedChange = { v ->
                    playbackSpeedState = v
                    settings.playbackSpeed = v
                    runCatching { controller?.setPlaybackSpeed(v) }
                },
                crossfadeSeconds = crossfadeSecondsState,
                onCrossfadeSecondsChange = { v ->
                    crossfadeSecondsState = v
                    settings.crossfadeSeconds = v
                },
                normalizeVolume = normalizeVolumeState,
                onNormalizeVolumeChange = { v ->
                    normalizeVolumeState = v
                    settings.normalizeVolume = v
                },
                accentName = AccentOptions.getOrNull(accentIndex)?.name ?: "Default",
                accentColor = AccentOptions.getOrNull(accentIndex)?.color ?: DefaultAccent,
                onOpenAccentPicker = { showColorPicker = true },
                onBack = { showSettings = false },
                signedIn = signedIn,
                syncConfigured = authRepo.configured,
                accountEmail = authRepo.currentUserEmail(),
                inviteCode = myProfile?.inviteCode,
                onSignIn = { showSignIn = true },
                onSignOut = {
                    scope.launch { runCatching { authRepo.signOut() } }
                },
                onCopyInvite = {
                    val code = myProfile?.inviteCode
                    if (!code.isNullOrBlank()) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Invite code", code))
                        addFriendStatus = "Invite code copied."
                    }
                },
                friends = friendsList,
                addFriendCode = addFriendCode,
                onAddFriendCodeChange = { addFriendCode = it; addFriendStatus = null },
                onAddFriend = {
                    scope.launch {
                        addFriendStatus = "Adding…"
                        profileRepo.addFriendByCode(addFriendCode)
                            .onSuccess {
                                addFriendStatus = "Friend added."
                                addFriendCode = ""
                                friendsList = runCatching { profileRepo.friends() }
                                    .getOrDefault(friendsList)
                            }
                            .onFailure { addFriendStatus = it.message ?: "Could not add friend." }
                    }
                },
                addFriendStatus = addFriendStatus,
                updateState = updateState,
                fastEngine = fastEngine,
                innerTubeFailCount = innerTubeFailCount,
                onCheckForUpdates = { scope.launch { updateRepo.check() } },
                onDownloadUpdate = { info -> scope.launch { updateRepo.download(info) } },
                onInstallUpdate = { file ->
                    val error = installUpdate(context, file)
                    if (error != null) {
                        scope.launch { snackbarHostState.showSnackbar(error) }
                    }
                },
                onImportAccount = {
                    showSettings = false
                    showImportAccount = true
                },
                onOpenDiagnostics = {
                    showSettings = false
                    showDiagnostics = true
                }
            )
        } else if (showDiagnostics) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showDiagnostics = false
                showSettings = true
            }
            com.sharl.musicapp.ui.screens.DiagnosticsScreen(
                onBack = {
                    showDiagnostics = false
                    showSettings = true
                },
                onCopy = { text ->
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("MusicApp diagnostics", text))
                    scope.launch { snackbarHostState.showSnackbar("Diagnostics copied.") }
                },
                onReset = { DiagnosticsManager.reset() }
            )
        } else if (showImportAccount) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showImportAccount = false
                showSettings = true
            }
            ImportAccountScreen(
                onBack = {
                    showImportAccount = false
                    showSettings = true
                },
                onOpenLinkImport = {
                    showImportAccount = false
                    importRepo.reset()
                    showImport = true
                }
            )
        } else if (showNewDropsAll) {
            androidx.activity.compose.BackHandler(enabled = true) {
                showNewDropsAll = false
            }
            NewDropsScreen(
                drops = newDrops,
                loading = newDropsLoading,
                downloadIds = downloadIds,
                onBack = { showNewDropsAll = false },
                onRefresh = {
                    scope.launch {
                        if (newDropsSeedArtists.isNotEmpty()) {
                            newDropsRepo.refresh(context, newDropsSeedArtists, followedArtists)
                        }
                    }
                },
                onPlay = { drop -> playFromQueue(listOf(QueueItem(drop.videoId, drop.title, drop.artist)), 0) },
                onLongClick = { drop -> menuSong = drop.asSong() },
                onToggleFavorite = { drop ->
                    scope.launch { repo.toggleFavorite(drop.asSong()) }
                }
            )
        } else if (showImport) {
            androidx.activity.compose.BackHandler(enabled = true) {
                importJob?.cancel()
                importJob = null
                importRepo.cancel()
                showImport = false
            }
            ImportScreen(
                state = importState,
                onSourceChosen = { src ->
                    importJob = scope.launch {
                        importRepo.preview(src, coroutineContext[Job])
                    }
                },
                onConfirm = { preview, downloadOffline ->
                    importJob = scope.launch { importRepo.commit(preview, downloadOffline) }
                },
                onOpenPlaylist = { playlistId ->
                    importJob = null
                    importRepo.reset()
                    showImport = false
                    tab = 2
                    libraryTab = 1
                    openPlaylistId = playlistId
                },
                onDismiss = {
                    importJob?.cancel()
                    importJob = null
                    importRepo.cancel()
                    showImport = false
                }
            )
        } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                    )
                    .clickable {
                        tab = 0
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "♪",
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary,
                    style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                )
            }
            Spacer(Modifier.width(12.dp))

            Text(
                text = when (tab) {
                    0 -> "Music"
                    1 -> "Search"
                    2 -> "Library"
                    3 -> "For You"
                    else -> "Music"
                },
                style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f)
            )

            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.IconButton(onClick = { showSettings = true }) {
                    Text("⚙", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
            }
        }

        when (tab) {
            0 -> {
                HomeTab(
                    forYouTop = forYouTop,
                    forYouRecent = forYouRecent,
                    onPlayFromQueue = { items, index -> playFromQueue(items, index) },
                    downloadIds = downloadIds,
                    loadingVideoId = loadingVideoId,
                    onSongMenu = { menuSong = it },
                    onSongMix = { startMix(it) },
                    signedIn = signedIn,
                    friendsNowPlaying = friendsNowPlaying,
                    friendsList = friendsList,
                    onFriendClick = { nowPlayingFriend = it },
                    newDrops = newDrops,
                    newDropsLoading = newDropsLoading,
                    onSeeAllDrops = { showNewDropsAll = true },
                    speedDialSlots = speedDialSlots,
                    onSpeedDialTap = { slot, position -> onSpeedDialTap(slot, position) },
                    onSpeedDialLongPress = { speedDialMenuPosition = it },
                    topArtistName = topArtistName,
                    forYouByArtist = forYouByArtist,
                    forYouFavorites = forYouFavorites,
                    isFollowingTopArtist = topArtistName?.let { a ->
                        followedArtists.any { it.equals(a, ignoreCase = true) }
                    } ?: false,
                    onToggleFollowTopArtist = { topArtistName?.let { toggleFollow(it) } },
                    // Phase 42: Disabled - depended on InnerTube streaming
                    // onDiscoverUnderground = { discoverUnderground() },
                    undergroundLoading = undergroundLoading,
                )
            }
            3 -> {
                ForYouScreen(
                    forYouRecent = forYouRecent,
                    forYouTop = forYouTop,
                    topArtistName = topArtistName,
                    forYouByArtist = forYouByArtist,
                    forYouFavorites = forYouFavorites,
                    startMix = { song -> startMix(song) },
                    modifier = Modifier.weight(1f)
                )
            }
            1 -> {
                SearchTab(
                    context = context,
                    query = query,
                    onQueryChange = { query = it },
                    results = results,
                    onResultsChange = { results = it },
                    isSearching = isSearching,
                    onSearchingChange = { isSearching = it },
                    localMatches = localMatches,
                    onLocalMatchesChange = { localMatches = it },
                    onSearchLocal = { q -> repo.searchLocal(q) },
                    scope = scope,
                    onPlayFromQueue = { items, index -> playFromQueue(items, index) },
                    onStartMix = { startMix(it) },
                    onToggleFavorite = { song -> scope.launch { repo.toggleFavorite(song) } },
                    onSongMenu = { menuSong = it },
                    onDownload = { videoId, title, artist ->
                        // Phase 43: Trigger permanent download to library
                        status = "Downloading to Library..."
                        loadingVideoId = videoId
                        scope.launch {
                            try {
                                Downloader.downloadSimple(context, settings, videoId, title, artist, youtubeThumb(videoId))
                                status = "Downloaded: $title"
                            } catch (e: Exception) {
                                status = "Download failed: ${e.message}"
                            } finally {
                                if (loadingVideoId == videoId) loadingVideoId = null
                            }
                        }
                    },
                    downloadIds = downloadIds,
                    loadingVideoId = loadingVideoId,
                )
            }
            2 -> {
                LibraryTab(
                    context = context,
                    scope = scope,
                    libraryTab = libraryTab,
                    onLibraryTabChange = { libraryTab = it },
                    libraryLayout = libraryLayout,
                    onLibraryLayoutChange = { libraryLayout = it },
                    onImportClick = { importRepo.reset(); showImport = true },
                    downloads = downloads,
                    downloadStates = downloadStates,
                    onDownloadMenuAction = { d, action ->
                        val song = Song(
                            videoId = d.videoId,
                            title = d.title,
                            artist = d.artist,
                            thumbnailUrl = d.thumbnailUrl,
                            lastPlayedAt = 0L,
                            playCount = 0
                        )
                        when (action) {
                            DownloadMenuAction.PLAY_NEXT -> insertIntoQueueNext(song)
                            DownloadMenuAction.ADD_TO_QUEUE -> appendToQueue(song)
                            DownloadMenuAction.START_MIX -> startMix(song)
                            DownloadMenuAction.GO_TO_ARTIST ->
                                if (d.artist.isNotBlank()) artistScreenName = d.artist
                        }
                    },
                    onRemoveAllDownloads = { showRemoveAllDownloads = true },
                    onFindMusic = { tab = 1 },
                    playlists = playlists,
                    openPlaylistId = openPlaylistId,
                    onOpenPlaylistChange = { openPlaylistId = it },
                    playlistRepo = playlistRepo,
                    onCreatePlaylist = { showNewPlaylistDialog = true },
                    onRequestRename = { name ->
                        renamePlaylistName = name
                        showRenamePlaylist = true
                    },
                    onRequestDelete = { showDeletePlaylistConfirm = true },
                    onPlaylistSongMenu = { ps ->
                        playlistMenuSong = ps
                        playlistMenuPlaylistId = openPlaylistId
                    },
                    onPlaylistMenu = { pl -> playlistListMenu = pl },
                    favorites = favorites,
                    onToggleFavorite = { song -> scope.launch { repo.toggleFavorite(song) } },
                    onSongMenu = { menuSong = it },
                    onDownloadDiscography = { name -> downloadDiscography(name) },
                    downloadIds = downloadIds,
                    loadingVideoId = loadingVideoId,
                    onPlayFromQueue = { items, index -> playFromQueue(items, index) },
                    friendsConfigured = authRepo.configured,
                    signedIn = signedIn,
                    friends = friendsList,
                    friendsLoading = friendsLoading,
                    friendsError = friendsError,
                    selectedFriend = selectedFriend,
                    selectedFriendSongs = selectedFriendSongs,
                    selectedFriendSongsLoading = selectedFriendSongsLoading,
                    friendsNowPlaying = friendsNowPlaying,
                    onOpenFriend = { f ->
                        selectedFriend = f
                        selectedFriendSongs = emptyList()
                        selectedFriendSongsLoading = true
                        scope.launch {
                            selectedFriendSongs = runCatching {
                                val shared = friendsRepo.friendsPlaylists(listOf(f.id)).firstOrNull()
                                if (shared?.id != null) friendsRepo.songsOf(shared.id) else emptyList()
                            }.getOrDefault(emptyList())
                            selectedFriendSongsLoading = false
                        }
                    },
                    onFriendBack = { selectedFriend = null },
                    onSignIn = { showSignIn = true },
                    localMedia = localMedia,
                    hasMediaPermission = mediaPermissionGranted,
                    onPlayLocal = { items, index -> playLocal(items, index) },
                    onRequestMediaPermission = { mediaPermissionLauncher.launch(mediaPermissions()) },
                    onRescanLocal = { scope.launch { localMediaRepo.refresh() } },
                    songDao = repo.getDao(), // Phase 44d: For artist queries in LibraryTab
                )
            }
        }

        if (nowPlayingTitle != null) {
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                ) {
                    Thumbnail(nowPlayingThumbnail, 56.dp)
                    if (queue.getOrNull(queueIndex)?.isVideo == true) {
                        Text(
                            "🎥",
                            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                                .padding(1.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { showNowPlaying = true }
                ) {
                    Text(
                        text = nowPlayingTitle ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = nowPlayingArtist ?: "",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { playPrev() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⏮", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable {
                            val c = controller ?: return@clickable
                            if (c.isPlaying) {
                                c.pause()
                                isPlaying = false
                            } else {
                                c.play()
                                isPlaying = true
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val miniLoading = loadingVideoId != null && loadingVideoId == queue.getOrNull(queueIndex)?.videoId
                    if (miniLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = androidx.compose.material3.MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            if (isPlaying) "⏸" else "▶",
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { playNext() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⏭", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)
                }
            }
            val progress = if (currentDurationMs > 0)
                (currentPosMs.toFloat() / currentDurationMs.toFloat()).coerceIn(0f, 1f)
            else 0f
            androidx.compose.material3.LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                trackColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {}
            )
        }

        NavigationBar(
            containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
            tonalElevation = 0.dp
        ) {
            val navItems = listOf(
                Triple(0, "Home", "🏠"),
                Triple(1, "Search", "🔍"),
                Triple(2, "Library", "📚"),
                Triple(3, "For You", "✨")
            )
            navItems.forEach { (idx, label, glyph) ->
                NavigationBarItem(
                    selected = tab == idx,
                    onClick = { tab = idx },
                    icon = {
                        Text(
                            glyph,
                            style = androidx.compose.material3.MaterialTheme.typography.titleLarge,
                            color = if (tab == idx)
                                androidx.compose.material3.MaterialTheme.colorScheme.primary
                            else
                                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    label = {
                        Text(
                            label,
                            style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                            color = if (tab == idx)
                                androidx.compose.material3.MaterialTheme.colorScheme.primary
                            else
                                androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                        indicatorColor = androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        selectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        selectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.primary,
                        unselectedIconColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
            }
        }

        if (showColorPicker) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showColorPicker = false },
                title = { Text("Accent color") },
                text = {
                    Column {
                        AccentOptions.forEachIndexed { idx, option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onAccentChange(idx)
                                        showColorPicker = false
                                    }
                                    .padding(vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .background(option.color)
                                )
                                androidx.compose.material3.Text(
                                    text = option.name,
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                                if (idx == accentIndex) {
                                    androidx.compose.material3.Text(
                                        text = "  ✓",
                                        modifier = Modifier.padding(start = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { showColorPicker = false }) {
                        Text("Close")
                    }
                }
            )
        }
        }

        val song = menuSong
        if (song != null) {
            val goToArtist: (() -> Unit)? = if (song.artist.isNotBlank()) {
                {
                    artistScreenName = song.artist
                    menuSong = null
                }
            } else null
            SongActionsSheet(
                song = song,
                downloadState = downloadStates[song.videoId],
                isDownloaded = downloadIds.contains(song.videoId),
                onDismiss = { menuSong = null },
                onPlayNext = {
                    insertIntoQueueNext(song)
                    menuSong = null
                },
                onAddToQueue = {
                    appendToQueue(song)
                    menuSong = null
                },
                onStartMix = { startMix(song) },
                onDownload = {
                    startDownload(
                        song.videoId, song.title, song.artist,
                        song.thumbnailUrl, 0L
                    )
                    menuSong = null
                },
                onRemoveDownload = {
                    scope.launch {
                        downloadRepo.get(song.videoId)?.let {
                            Downloader.remove(context, it)
                        }
                    }
                    menuSong = null
                },
                onGoToArtist = goToArtist,
                extraActions = {
                    MenuItem("Add to playlist") {
                        showAddToPlaylist = song
                        menuSong = null
                    }
                    MenuItem(
                        if (song.isFavorite) "Remove from favorites" else "Add to favorites"
                    ) {
                        scope.launch { repo.toggleFavorite(song) }
                        menuSong = null
                    }
                    MenuItem("Not interested") {
                        notInterested(song)
                        menuSong = null
                    }
                }
            )
        }

        val queued = queueMenuItem
        if (queued != null) {
            val song = Song(
                videoId = queued.videoId,
                title = queued.title,
                artist = queued.artist,
                thumbnailUrl = youtubeThumb(queued.videoId),
                lastPlayedAt = 0L,
                playCount = 0
            )
            SongActionsSheet(
                song = song,
                downloadState = downloadStates[song.videoId],
                isDownloaded = downloadIds.contains(song.videoId),
                onDismiss = { queueMenuItem = null },
                onPlayNext = {
                    moveQueueItemNext(queued)
                    queueMenuItem = null
                },
                onAddToPlaylist = {
                    showAddToPlaylist = song
                    queueMenuItem = null
                },
                onDownload = {
                    startDownload(song.videoId, song.title, song.artist, song.thumbnailUrl, 0L)
                    queueMenuItem = null
                },
                onRemoveDownload = {
                    scope.launch {
                        downloadRepo.get(song.videoId)?.let { Downloader.remove(context, it) }
                    }
                    queueMenuItem = null
                },
                extraActions = {
                    MenuItem("Remove from queue") {
                        removeFromQueue(queued)
                        queueMenuItem = null
                    }
                }
            )
        }

        nowPlayingFriend?.let { np ->
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { nowPlayingFriend = null }
            ) {
                Column(modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                    val who = friendsList.firstOrNull { it.id == np.userId }
                    Text(
                        "Last seen ${formatRelative(np.updatedAt)}",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        who?.displayName ?: who?.email ?: "A friend",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Thumbnail(np.videoId?.let { youtubeThumb(it) }, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(np.title ?: "", maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                np.artist ?: "",
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (showNewPlaylistDialog) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showNewPlaylistDialog = false; newPlaylistName = "" },
                title = { Text("New playlist") },
                text = {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                            val name = newPlaylistName.trim()
                            if (name.isNotBlank()) {
                                scope.launch { playlistRepo.create(name) }
                                newPlaylistName = ""
                                showNewPlaylistDialog = false
                            }
                        }
                    ) { Text("Create") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showNewPlaylistDialog = false; newPlaylistName = "" }
                    ) { Text("Cancel") }
                }
            )
        }

        val addSong = showAddToPlaylist
        if (addSong != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showAddToPlaylist = null },
                title = { Text("Add to playlist") },
                text = {
                    Column {
                        if (playlists.isEmpty()) {
                            Text("No playlists yet. Create one first.")
                        } else {
                            LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                                items(playlists, key = { it.id }) { pl ->
                                    androidx.compose.material3.TextButton(
                                        onClick = {
                                            scope.launch {
                                                playlistRepo.addSong(
                                                    pl.id,
                                                    addSong.videoId,
                                                    addSong.title,
                                                    addSong.artist,
                                                    addSong.thumbnailUrl
                                                )
                                            }
                                            showAddToPlaylist = null
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(pl.name, modifier = Modifier.fillMaxWidth())
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        showAddToPlaylist = null
                        showPlaylists = true
                    }) { Text("Manage playlists") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showAddToPlaylist = null }
                    ) { Text("Cancel") }
                }
            )
        }

        if (showRenamePlaylist && openPlaylistId != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRenamePlaylist = false },
                title = { Text("Rename playlist") },
                text = {
                    OutlinedTextField(
                        value = renamePlaylistName,
                        onValueChange = { renamePlaylistName = it },
                        label = { Text("Name") },
                        singleLine = true
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val name = renamePlaylistName.trim()
                        val id = openPlaylistId
                        if (name.isNotBlank() && id != null) {
                            scope.launch { playlistRepo.rename(id, name) }
                        }
                        showRenamePlaylist = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showRenamePlaylist = false }
                    ) { Text("Cancel") }
                }
            )
        }

        if (showDeletePlaylistConfirm && openPlaylistId != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeletePlaylistConfirm = false },
                title = { Text("Delete playlist?") },
                text = { Text("This cannot be undone.") },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        val id = openPlaylistId
                        if (id != null) {
                            scope.launch { playlistRepo.delete(id) }
                        }
                        openPlaylistId = null
                        showDeletePlaylistConfirm = false
                    }) { Text("Delete") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(
                        onClick = { showDeletePlaylistConfirm = false }
                    ) { Text("Cancel") }
                }
            )
        }

        val ps = playlistMenuSong
        if (ps != null && playlistMenuPlaylistId != null) {
            val psGoToArtist: (() -> Unit)? = if (ps.artist.isNotBlank()) {
                {
                    artistScreenName = ps.artist
                    playlistMenuSong = null
                }
            } else null
            SongActionsSheet(
                song = ps,
                downloadState = downloadStates[ps.videoId],
                isDownloaded = downloadIds.contains(ps.videoId),
                onDismiss = { playlistMenuSong = null },
                onPlayNext = {
                    insertIntoQueueNext(ps)
                    playlistMenuSong = null
                },
                onAddToQueue = {
                    appendToQueue(ps)
                    playlistMenuSong = null
                },
                onStartMix = { startMix(ps) },
                onDownload = {
                    startDownload(
                        ps.videoId, ps.title, ps.artist,
                        ps.thumbnailUrl, 0L
                    )
                    playlistMenuSong = null
                },
                onRemoveDownload = {
                    scope.launch {
                        downloadRepo.get(ps.videoId)?.let {
                            Downloader.remove(context, it)
                        }
                    }
                    playlistMenuSong = null
                },
                onGoToArtist = psGoToArtist,
                extraActions = {
                    MenuItem("Remove from playlist") {
                        val pid = playlistMenuPlaylistId!!
                        scope.launch { playlistRepo.removeSong(pid, ps.videoId) }
                        playlistMenuSong = null
                    }
                }
            )
        }

        val sdPos = speedDialMenuPosition
        if (sdPos != null) {
            val existing = speedDialSlots.firstOrNull { it.position == sdPos }
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { speedDialMenuPosition = null }
            ) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    Text(
                        "Slot ${sdPos + 1}",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                    HorizontalDivider()

                    MenuItem("Shuffle Favorites") {
                        scope.launch { speedDialRepo.assignShuffleFavorites(sdPos) }
                        speedDialMenuPosition = null
                    }
                    MenuItem("Shuffle Recently Played") {
                        scope.launch { speedDialRepo.assignShuffleRecent(sdPos) }
                        speedDialMenuPosition = null
                    }
                    MenuItem("Choose a song") {
                        showSongPickerForSlot = sdPos
                        speedDialMenuPosition = null
                    }
                    if (existing != null && existing.type != "empty") {
                        MenuItem("Remove") {
                            scope.launch { speedDialRepo.clear(sdPos) }
                            speedDialMenuPosition = null
                        }
                    }
                }
            }
        }

        val plMenu = playlistListMenu
        if (plMenu != null) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { playlistListMenu = null }
            ) {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    Text(
                        plMenu.name,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    HorizontalDivider()

                    MenuItem("Download all ($playlistListSongCount)") {
                        val pid = plMenu.id
                        scope.launch {
                            try {
                                val songs = playlistRepo.songs(pid).first()
                                songs.filterNot { downloadIds.contains(it.videoId) }
                                    .forEach { ps ->
                                        try {
                                            Downloader.download(
                                                context, settings, ps.videoId, ps.title,
                                                ps.artist, ps.thumbnailUrl, 0L
                                            )
                                        } catch (e: Exception) {
                                            Log.w("DOWNLOAD", "playlist download failed ${ps.videoId}", e)
                                        }
                                    }
                            } catch (e: Exception) {
                                status = "Download all failed: ${e.message}"
                            }
                        }
                        playlistListMenu = null
                    }
                    if (playlistListDownloaded > 0) {
                        MenuItem("Remove downloads ($playlistListDownloaded of $playlistListSongCount)") {
                            val pid = plMenu.id
                            scope.launch {
                                val songs = playlistRepo.songs(pid).first()
                                songs.filter { downloadIds.contains(it.videoId) }
                                    .forEach { ps ->
                                        downloadRepo.get(ps.videoId)?.let {
                                            Downloader.remove(context, it)
                                        }
                                    }
                            }
                            playlistListMenu = null
                        }
                    }
                    if (signedIn) {
                        if (playlistListIsShared) {
                            MenuItem("Stop sharing") {
                                scope.launch { runCatching { friendsRepo.stopSharing() } }
                                playlistListIsShared = false
                                playlistListMenu = null
                            }
                        } else {
                            MenuItem(
                                "Share with friends",
                                subtitle = "Snapshot — re-share to update."
                            ) {
                                val pid = plMenu.id
                                val pname = plMenu.name
                                scope.launch {
                                    runCatching { friendsRepo.sharePlaylist(context, pid, pname) }
                                        .onSuccess { playlistListIsShared = true }
                                        .onFailure { friendsError = it.message }
                                }
                                playlistListMenu = null
                            }
                        }
                    }
                }
            }
        }

        if (showClearLibraryConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showClearLibraryConfirm = false },
                title = { Text("Clear library?") },
                text = {
                    Text(
                        "This removes all songs, playlists, favorites, speed dial slots, " +
                            "and play history from MusicApp.\n\n" +
                            "Downloaded audio files are NOT removed — use \"Remove all downloads\" " +
                            "for that.\n\n" +
                            "This cannot be undone."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        clearLibrary()
                        showClearLibraryConfirm = false
                    }) {
                        Text(
                            "Clear",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showClearLibraryConfirm = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        if (showRemoveAllDownloads) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showRemoveAllDownloads = false },
                title = { Text("Remove all downloads?") },
                text = {
                    Text(
                        "This deletes every downloaded song from device storage.\n\n" +
                            "Your library, playlists, and favorites stay intact — only the " +
                            "offline audio files are removed.\n\n" +
                            "You can re-download songs later."
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        removeAllDownloads()
                        showRemoveAllDownloads = false
                    }) {
                        Text(
                            "Remove",
                            color = androidx.compose.material3.MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showRemoveAllDownloads = false }) {
                        Text("Cancel")
                    }
                }
            )
        }

        removalProgress?.let { (done, total) ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = {},
                title = { Text("Removing downloads") },
                text = { Text("Removing $done of $total…") },
                confirmButton = {}
            )
        }
    }

        val videoItem = queue.getOrNull(queueIndex)
        if (showNowPlaying && isVideoMode && videoItem?.isVideo == true) {
            VideoPlayerScreen(
                mediaController = controller,
                title = nowPlayingTitle ?: videoItem.title,
                artist = nowPlayingArtist ?: videoItem.artist,
                onBack = { showNowPlaying = false },
                onAudioOnly = { isVideoMode = false }
            )
        }
    }
}

/**
 * Hands the downloaded APK to the system package installer.
 * Returns null on success, or a user-facing error message on failure.
 */
fun installUpdate(context: Context, file: File): String? {
    return try {
        if (!file.exists() || file.length() == 0L) {
            return "Update file is missing. Please download it again."
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            return try {
                context.startActivity(
                    Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                        .setData(android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                "Allow \"Install unknown apps\" for MusicApp, then tap Install update again."
            } catch (e: Exception) {
                Log.w(OTA_TAG, "could not open unknown-sources settings", e)
                "Allow installation from unknown sources in system settings, then try again."
            }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            clipData = android.content.ClipData.newRawUri("update", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Some installers rely on the explicit grant rather than the intent flag.
        context.packageManager.queryIntentActivities(intent, 0).forEach { resolved ->
            runCatching {
                context.grantUriPermission(
                    resolved.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        context.startActivity(intent)
        null
    } catch (e: Exception) {
        Log.w(OTA_TAG, "failed to launch installer", e)
        "Failed to launch installer: ${e.message ?: e.javaClass.simpleName}"
    }
}

private const val OTA_TAG = "OTA"

@Preview(showBackground = true)
@Composable
fun PlayerScreenPreview() {
    MusicAppTheme {
        PlayerScreen(accentIndex = 0, onAccentChange = {})
    }
}
