package com.sharl.musicapp.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.sharl.musicapp.LyricLine
import com.sharl.musicapp.QueueItem
import com.sharl.musicapp.ui.components.Thumbnail
import com.sharl.musicapp.ui.util.formatTime
import kotlinx.coroutines.delay

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
    onStartMix: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    skipSeconds: Int = 15,
    onSkipBack: () -> Unit = {},
    onSkipForward: () -> Unit = {},
    playbackSpeed: Float = 1f,
    onPlaybackSpeedChange: (Float) -> Unit = {},
    onNotInterested: () -> Unit = {},
    onShuffleToggle: () -> Unit,
    onRepeatToggle: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp)
    ) {
        // Top bar: back on the left, "not interested" on the right so the
        // current track can be hidden without opening its menu.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onBack) {
                    Text(
                        "←",
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onNotInterested) {
                Text("👎", style = MaterialTheme.typography.titleLarge)
            }
        }

        TabRow(selectedTabIndex = nowPlayingTab) {
            Tab(
                selected = nowPlayingTab == 0,
                onClick = { onNowPlayingTabChange(0) },
                text = { Text("Art") }
            )
            Tab(
                selected = nowPlayingTab == 1,
                onClick = { onNowPlayingTabChange(1) },
                text = { Text("Lyrics") }
            )
            Tab(
                selected = nowPlayingTab == 2,
                onClick = { onNowPlayingTabChange(2) },
                text = {
                    Text(
                        queueTabLabel,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
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
                // Artwork (or video), centered in whatever vertical space is
                // left so the transport row below can never be pushed off-screen.
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
                        )
                    } else {
                        val artSize = minOf(maxWidth, maxHeight)
                        Thumbnail(thumbnailUrl, artSize)
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))

                // Title and artist, centered; the speed chip sits at the end
                // and opens the quick speed slider.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Spacer(Modifier.size(48.dp))
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clickable {
                                speedDraft = playbackSpeed
                                showSpeedDialog = true
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            String.format(java.util.Locale.US, "%.1fx", playbackSpeed),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                if (isLoading && showExtracting) {
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "Extracting audio…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.size(8.dp))

                // Video/audio toggle
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(56.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        IconButton(
                            onClick = { onToggleVideoMode() },
                            enabled = !videoModeLoading
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
                    }
                    if (videoModeLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
            1 -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when {
                        lyricsLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                        lyrics.isEmpty() -> {
                            Text(
                                "No lyrics available.",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
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
                                            MaterialTheme.typography.titleMedium
                                        else
                                            MaterialTheme.typography.bodyMedium,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp, horizontal = 16.dp)
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
                        Text("Queue is empty.")
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
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                } else {
                                    Text(
                                        "${idx + 1}.",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(end = 8.dp)
                                    )
                                }
                                Text(
                                    text = item.title,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        // Progress bar + time labels (visible in all tabs)
        val displayPos = if (isScrubbing) scrubPositionMs else currentPosMs
        val maxVal = currentDurationMs.coerceAtLeast(1L).toFloat()
        Slider(
            value = displayPos.coerceIn(0L, currentDurationMs.coerceAtLeast(0L)).toFloat(),
            onValueChange = { v -> if (!isScrubbing) onScrubStart(); onScrubChange(v.toLong()) },
            onValueChangeFinished = { onScrubEnd() },
            valueRange = 0f..maxVal,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
        ) {
            Text(
                formatTime(displayPos),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                formatTime(currentDurationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Shuffle, transport and repeat in one row: the artwork above is
        // flexible, so these controls always stay on screen.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onStartMix) {
                    Text(
                        "📻",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onShuffleToggle) {
                    Text(
                        "🔀",
                        style = MaterialTheme.typography.titleLarge,
                        color = if (isShuffled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onSkipBack) {
                    Text(
                        "−${skipSeconds}s",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
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
                        modifier = Modifier.size(32.dp),
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
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onSkipForward) {
                    Text(
                        "+${skipSeconds}s",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = onRepeatToggle) {
                    val glyph = when (repeatMode) {
                        1 -> "🔁"
                        2 -> "🔂"
                        else -> "🔁"
                    }
                    Text(
                        glyph,
                        style = MaterialTheme.typography.titleLarge,
                        color = if (repeatMode == 0)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.size(16.dp))

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
