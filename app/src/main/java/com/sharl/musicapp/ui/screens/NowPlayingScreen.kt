package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.sharl.musicapp.LyricLine
import com.sharl.musicapp.QueueItem
import com.sharl.musicapp.R
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.util.formatTime
import kotlinx.coroutines.delay

/** Sanitizes display titles: replaces raw YouTube IDs with "Unknown Track". */
private fun sanitizeTitle(title: String): String {
    val trimmed = title.trim()
    // Raw YouTube video IDs are exactly 11 chars of [A-Za-z0-9_-]
    return if (trimmed.length == 11 && trimmed.matches(Regex("^[A-Za-z0-9_-]+$"))) {
        "Unknown Track"
    } else {
        trimmed.ifBlank { "Unknown Track" }
    }
}

@Composable
fun NowPlayingScreen(
    title: String,
    artist: String,
    thumbnailUrl: String?,
    isPlaying: Boolean,
    isLoading: Boolean,
    isShuffled: Boolean,
    repeatMode: Int,
    lyrics: List<LyricLine>,
    lyricsLoading: Boolean,
    currentPosMs: Long,
    currentDurationMs: Long,
    isScrubbing: Boolean,
    scrubPositionMs: Long,
    onScrubStart: () -> Unit,
    onScrubChange: (Long) -> Unit,
    onScrubEnd: () -> Unit,
    isVideoMode: Boolean,
    videoModeLoading: Boolean,
    onToggleVideoMode: () -> Unit,
    mediaController: MediaController?,
    nowPlayingTab: Int,
    onNowPlayingTabChange: (Int) -> Unit,
    queue: List<QueueItem>,
    queueIndex: Int,
    queueTabLabel: String = "Queue",
    onQueueItemClick: (Int) -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    skipSeconds: Int = 15,
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    onSkipPrevious: () -> Unit = {},
    onSkipNext: () -> Unit = {},
    playbackSpeed: Float = 1f,
    onPlaybackSpeedChange: (Float) -> Unit = {},
    onNotInterested: () -> Unit = {},
    onArtistClick: () -> Unit = {},
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit
) {
    val displayTitle = sanitizeTitle(title)
    val displayArtist = artist.trim().ifBlank { "Unknown Artist" }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        // Top bar: back on the left, "not interested" on the right
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    "←",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = onNotInterested,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    "👎",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        TabRow(
            selectedTabIndex = nowPlayingTab,
            modifier = Modifier.padding(bottom = 8.dp)
        ) {
            Tab(
                selected = nowPlayingTab == 0,
                onClick = { onNowPlayingTabChange(0) },
                text = { 
                    Text(
                        "Art",
                        style = MaterialTheme.typography.labelLarge
                    ) 
                }
            )
            Tab(
                selected = nowPlayingTab == 1,
                onClick = { onNowPlayingTabChange(1) },
                text = { 
                    Text(
                        "Lyrics",
                        style = MaterialTheme.typography.labelLarge
                    ) 
                }
            )
            Tab(
                selected = nowPlayingTab == 2,
                onClick = { onNowPlayingTabChange(2) },
                text = {
                    Text(
                        queueTabLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            )
        }

        val queueListState = rememberLazyListState()

        var showExtracting by remember { mutableStateOf(false) }
        var showSpeedDialog by remember { mutableStateOf(false) }
        var speedDraft by remember(playbackSpeed) { mutableStateOf(playbackSpeed) }
        LaunchedEffect(isLoading) {
            if (isLoading) {
                delay(2000)
                showExtracting = true
            } else {
                showExtracting = false
            }
        }

        LaunchedEffect(nowPlayingTab, queueIndex) {
            if (nowPlayingTab == 2 && queueIndex >= 0) {
                queueListState.animateScrollToItem(queueIndex)
            }
        }

        when (nowPlayingTab) {
            0 -> {
                // Artwork (or video), centered with proper spacing
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (isVideoMode) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    useController = false
                                    player = mediaController
                                }
                            },
                            update = { view ->
                                (view as PlayerView).player = mediaController
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    } else {
                        val artSize = minOf(maxWidth * 0.85f, maxHeight * 0.85f)
                        Thumbnail(thumbnailUrl, artSize)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Title and artist, centered with proper typography
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = MaterialTheme.typography.titleLarge.fontSize
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = displayArtist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = displayArtist != "Unknown Artist", onClick = onArtistClick)
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                if (isLoading && showExtracting) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Extracting audio…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Video/audio toggle with larger touch target
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { onToggleVideoMode() },
                        enabled = !videoModeLoading,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Text(
                            if (isVideoMode) "🎬" else "🎵",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (isVideoMode)
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (videoModeLoading) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
            1 -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                ) {
                    when {
                        lyricsLoading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    strokeWidth = 3.dp
                                )
                            }
                        }
                        lyrics.isEmpty() -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "🎵",
                                        style = MaterialTheme.typography.displaySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                    )
                                    Text(
                                        "No lyrics available",
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        textAlign = TextAlign.Center
                                    )
                                    Text(
                                        "Lyrics will appear here when available",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        else -> {
                            val currentIndex = lyrics.indexOfLast { it.startMs <= currentPosMs }
                            val listState = rememberLazyListState()
                            LaunchedEffect(currentIndex) {
                                if (currentIndex >= 0) listState.animateScrollToItem(currentIndex)
                            }
                            LazyColumn(
                                state = listState,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                            ) {
                                itemsIndexed(lyrics) { idx, line ->
                                    val active = idx == currentIndex
                                    Text(
                                        text = line.text,
                                        color = if (active)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = if (active)
                                            MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                        else
                                            MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp, horizontal = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
            2 -> {
                if (queue.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "🎶",
                                style = MaterialTheme.typography.displaySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Text(
                                "Queue is empty",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                "Add songs to start listening",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        state = queueListState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        itemsIndexed(queue) { idx, item ->
                            val isCurrent = idx == queueIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onQueueItemClick(idx) }
                                    .padding(vertical = 12.dp, horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isCurrent) {
                                    Text(
                                        "▶",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                } else {
                                    Text(
                                        "${idx + 1}.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                }
                                Text(
                                    text = sanitizeTitle(item.title),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (isCurrent)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (idx < queue.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Progress bar + time labels with proper spacing
        Spacer(modifier = Modifier.height(8.dp))
        val displayPos = if (isScrubbing) scrubPositionMs else currentPosMs
        val maxVal = currentDurationMs.coerceAtLeast(1L).toFloat()
        Slider(
            value = displayPos.coerceIn(0L, currentDurationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = { v -> if (!isScrubbing) onScrubStart(); onScrubChange(v.toLong()) },
            onValueChangeFinished = { onScrubEnd() },
            valueRange = 0f..maxVal,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatTime(displayPos),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            // Playback speed chip with better touch target
            Surface(
                onClick = {
                    speedDraft = playbackSpeed
                    showSpeedDialog = true
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                modifier = Modifier.padding(horizontal = 4.dp)
            ) {
                Text(
                    text = String.format(java.util.Locale.US, "%.1fx", playbackSpeed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                formatTime(currentDurationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Action row: Download, Favourite, Add to playlist (horizontally scrollable)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
        ) {
            ActionPillButton(
                icon = "⬇",
                label = "Download",
                onClick = { /* TODO: wire download */ }
            )
            ActionPillButton(
                icon = "♡",
                label = "Favourite",
                onClick = { /* TODO: wire favorite */ }
            )
            ActionPillButton(
                icon = "➕",
                label = "Playlist",
                onClick = { /* TODO: wire add to playlist */ }
            )
            ActionPillButton(
                icon = "↗",
                label = "Share",
                onClick = { /* TODO: wire share */ }
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Transport controls: Shuffle | Previous | −15s | Play | +15s | Next | Repeat
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            TransportButton(
                onClick = onShuffleToggle,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    "🔀",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isShuffled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TransportButton(
                onClick = onSkipPrevious,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    "⏮",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            TransportButton(
                onClick = onSkipBack,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    "−${skipSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // Play/Pause button - larger and more prominent
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(enabled = !isLoading) { onPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    TransportIcon(
                        kind = if (isPlaying) TransportKind.Pause else TransportKind.Play,
                        size = 32.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            
            TransportButton(
                onClick = onSkipForward,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    "+${skipSeconds}s",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            TransportButton(
                onClick = onSkipNext,
                modifier = Modifier.size(48.dp)
            ) {
                Text(
                    "⏭",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            TransportButton(
                onClick = onRepeatToggle,
                modifier = Modifier.size(48.dp)
            ) {
                val glyph = when (repeatMode) {
                    1 -> "🔁"
                    2 -> "🔂"
                    else -> "🔁"
                }
                Text(
                    glyph,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (repeatMode == 0)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (showSpeedDialog) {
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = { Text("Playback speed") },
                text = {
                    Column {
                        Text(
                            String.format(java.util.Locale.US, "%.1fx", speedDraft),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Slider(
                            value = speedDraft,
                            onValueChange = { speedDraft = it },
                            valueRange = 0.5f..5f,
                            steps = 8
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        onPlaybackSpeedChange(speedDraft)
                        showSpeedDialog = false
                    }) { Text("Done") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        speedDraft = playbackSpeed
                        showSpeedDialog = false
                    }) { Text("Cancel") }
                }
            )
        }
    }
}

/** Pill-shaped action button for the Now Playing action row. */
@Composable
private fun ActionPillButton(
    icon: String,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.height(40.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                icon,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** Transport control button with consistent sizing and touch target. */
@Composable
private fun TransportButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

private enum class TransportKind { Play, Pause }

/** Vector transport glyphs, drawn locally so the screen needs no icon artifact. */
@Composable
private fun TransportIcon(kind: TransportKind, size: Dp, color: Color) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        when (kind) {
            TransportKind.Play -> {
                drawPath(
                    Path().apply {
                        moveTo(w * 0.30f, h * 0.16f)
                        lineTo(w * 0.30f, h * 0.84f)
                        lineTo(w * 0.84f, h * 0.50f)
                        close()
                    },
                    color
                )
            }
            TransportKind.Pause -> {
                val barW = w * 0.20f
                val barH = h * 0.68f
                val top = (h - barH) / 2f
                drawRoundRect(color, Offset(w * 0.22f, top), Size(barW, barH), CornerRadius(barW / 4f))
                drawRoundRect(color, Offset(w * 0.58f, top), Size(barW, barH), CornerRadius(barW / 4f))
            }
        }
    }
}
