package com.sharl.musicapp

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.sharl.musicapp.ui.screens.NowPlayingScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class QueueScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun repeatedSongOccurrencesRenderAndSelectIndependently() {
        val first = QueueItem("sameVideoId", "First occurrence", "Artist")
        val second = QueueItem("sameVideoId", "Second occurrence", "Artist")
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                NowPlayingScreen(
                    title = "Current", artist = "Artist", thumbnailUrl = null,
                    isPlaying = false, isLoading = false, isShuffled = false, repeatMode = 0,
                    lyrics = emptyList(), lyricsLoading = false, currentPosMs = 0,
                    currentDurationMs = 60_000, isScrubbing = false, scrubPositionMs = 0,
                    onScrubStart = {}, onScrubChange = {}, onScrubEnd = {},
                    isVideoMode = false, videoModeLoading = false, onToggleVideoMode = {},
                    mediaController = null, nowPlayingTab = 2, onNowPlayingTabChange = {},
                    queue = listOf(first, second), queueIndex = 0,
                    onQueueItemClick = { selected = it }, onBack = {}, onPlayPause = {},
                    onShuffleToggle = {}, onRepeatToggle = {}
                )
            }
        }
        compose.onAllNodesWithText("First occurrence").assertCountEquals(1)
        compose.onAllNodesWithText("Second occurrence").assertCountEquals(1)
        compose.onNodeWithText("Second occurrence").performClick()
        compose.runOnIdle { assertEquals(second.uid, selected) }
    }
}
