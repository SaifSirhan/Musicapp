package com.sharl.musicapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackFilterTest {

    @Test
    fun parsesMinuteAndHourTimestamps() {
        assertEquals(265_000L, InnerTubeHelper.parseDurationMs("4:25"))
        assertEquals(5_418_000L, InnerTubeHelper.parseDurationMs("1:30:18"))
        assertNull(InnerTubeHelper.parseDurationMs("Now playing"))
        assertNull(InnerTubeHelper.parseDurationMs(""))
    }

    @Test
    fun dropsTracksOutsideThirtySecondsToFifteenMinutes() {
        assertTrue(InnerTubeHelper.isJunkTrack("Interlude", 20_000L))
        assertTrue(InnerTubeHelper.isJunkTrack("Long set", 15L * 60_000 + 1))
        assertFalse(InnerTubeHelper.isJunkTrack("Normal song", 3L * 60_000))
        assertFalse(InnerTubeHelper.isJunkTrack("Unknown length", null))
    }

    @Test
    fun dropsJunkTitlesButKeepsRemixAndOfficialAudio() {
        assertTrue(InnerTubeHelper.isJunkTrack("Song (Lyrics)", 180_000L))
        assertTrue(InnerTubeHelper.isJunkTrack("Song (Live at Wembley)", 180_000L))
        assertTrue(InnerTubeHelper.isJunkTrack("Nightcore Mix 2026", 180_000L))
        assertTrue(InnerTubeHelper.isJunkTrack("10 Hours of Rain", 180_000L))
        assertTrue(InnerTubeHelper.isJunkTrack("Podcast Episode 4", 180_000L))
        assertTrue(InnerTubeHelper.isJunkTrack("Guitar Tutorial", 180_000L))
        assertFalse(InnerTubeHelper.isJunkTrack("Song (Official Audio)", 180_000L))
        assertFalse(InnerTubeHelper.isJunkTrack("Song (Remix)", 180_000L))
    }
}
