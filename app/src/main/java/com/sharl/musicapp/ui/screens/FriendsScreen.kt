package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sharl.musicapp.data.NowPlaying
import com.sharl.musicapp.data.Profile
import com.sharl.musicapp.data.SharedPlaylistSong
import com.sharl.musicapp.ui.components.SharedSongRow
import com.sharl.musicapp.ui.util.formatRelative
import com.sharl.musicapp.ui.util.parseIsoMs

@Composable
fun FriendsScreen(
    configured: Boolean,
    signedIn: Boolean,
    friends: List<Profile>,
    loading: Boolean,
    error: String?,
    selectedFriend: Profile?,
    friendSongs: List<SharedPlaylistSong>,
    friendSongsLoading: Boolean,
    nowPlaying: List<NowPlaying>,
    onBack: () -> Unit,
    onBackToList: () -> Unit,
    onOpenFriend: (Profile) -> Unit,
    onPlayFriendSongs: (Int) -> Unit,
    onSignIn: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (selectedFriend != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = onBackToList) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Text(
                    selectedFriend.displayName ?: selectedFriend.email ?: "Friend",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        when {
            !configured -> CenteredMessage(
                "Sync isn't configured. Add your Supabase + Google keys to secrets.properties, then rebuild.",
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            !signedIn -> Column(
                modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "Sign in to sync playlists and see what friends are playing.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Button(onClick = onSignIn) { Text("Sign in") }
            }
            selectedFriend != null -> when {
                friendSongsLoading -> Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                friendSongs.isEmpty() -> CenteredMessage(
                    "This friend hasn't shared a playlist.",
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                else -> Column(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        "Snapshot — re-share to update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        itemsIndexed(friendSongs, key = { _, s -> s.videoId }) { idx, s ->
                            SharedSongRow(s) { onPlayFriendSongs(idx) }
                        }
                    }
                }
            }
            loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            error != null -> CenteredMessage(
                error,
                modifier = Modifier.fillMaxWidth().weight(1f),
                isError = true
            )
            friends.isEmpty() -> CenteredMessage(
                "No friends yet. Add one by invite code in Settings.",
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            else -> LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                items(friends, key = { it.id }) { f ->
                    val np = nowPlaying.firstOrNull { it.userId == f.id }
                    val staleMs = np?.updatedAt?.let { parseIsoMs(it) }?.let {
                        System.currentTimeMillis() - it
                    }
                    val subtitle = if (staleMs != null && staleMs > 2 * 60_000) {
                        "Last seen ${formatRelative(np?.updatedAt)}"
                    } else {
                        f.email ?: ""
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenFriend(f) }
                            .padding(vertical = 12.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            f.displayName ?: f.email ?: "Friend",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier, isError: Boolean = false) {
    Box(
        modifier = modifier.padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            textAlign = TextAlign.Center,
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
