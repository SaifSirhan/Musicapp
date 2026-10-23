package com.sharl.musicapp.data.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleNormalizerTest {

    @Test
    fun stripsOnlyPresentationDecoration() {
        assertEquals("one more time", TitleNormalizer.normalize("One More Time (Official Audio)").core)
        assertEquals("one more time", TitleNormalizer.normalize("One More Time [Official Music Video]").core)
        assertEquals("one more time", TitleNormalizer.normalize("One More Time - Official Video").core)
        assertEquals("one more time", TitleNormalizer.normalize("Official Video: One More Time").core)
        assertEquals("one more time", TitleNormalizer.normalize("One More Time (Lyrics)").core)
        assertEquals("one more time", TitleNormalizer.normalize("One More Time (M/V)").core)
    }

    @Test
    fun keepsMeaningfulWordsThatLookLikeDecoration() {
        assertEquals("video killed the radio star", TitleNormalizer.normalize("Video Killed the Radio Star").core)
        assertEquals("audio", TitleNormalizer.normalize("Audio").core)
        assertEquals("song music box", TitleNormalizer.normalize("Song (Music Box)").core)
        assertEquals("song with you", TitleNormalizer.normalize("Song (With You)").core)
    }

    @Test
    fun preservesVersionAndPartMarkers() {
        assertEquals("song part 2", TitleNormalizer.normalize("Song, Pt. 2").core)
        assertEquals("song part 2", TitleNormalizer.normalize("Song Part II").core)
        assertEquals("song part 2", TitleNormalizer.normalize("Song (Part 2)").core)
        assertEquals("song remix", TitleNormalizer.normalize("Song (Remix)").core)
        assertEquals("song live at wembley", TitleNormalizer.normalize("Song (Live at Wembley)").core)
        assertEquals("song acoustic version", TitleNormalizer.normalize("Song - Acoustic Version").core)
    }

    @Test
    fun detectsVariantsFromDecorationPositions() {
        assertTrue(TitleNormalizer.normalize("Song (Remix)").variants.contains("remix"))
        assertTrue(TitleNormalizer.normalize("Song (Live at Wembley)").variants.contains("live"))
        assertTrue(TitleNormalizer.normalize("Song - Remix").variants.contains("remix"))
        assertTrue(TitleNormalizer.normalize("Song (Sped Up)").variants.contains("sped_up"))
        assertTrue(TitleNormalizer.normalize("Song Live").variants.contains("live"))
        assertTrue(TitleNormalizer.normalize("Song (Official Audio)").variants.isEmpty())
    }

    @Test
    fun doesNotTreatOrdinaryTitleWordsAsVariants() {
        assertTrue(TitleNormalizer.normalize("Live Forever").variants.isEmpty())
        assertTrue(TitleNormalizer.normalize("Live and Let Die").variants.isEmpty())
        assertTrue(TitleNormalizer.normalize("Cover Me").variants.isEmpty())
    }

    @Test
    fun foldsUnicodeDiacriticsAndPunctuationButKeepsLetters() {
        // Dotless i is a distinct letter, not a diacritic; it must survive folding.
        assertEquals("sımarık", TitleNormalizer.normalize("Şımarık").core)
        assertEquals("夜に駆ける", TitleNormalizer.normalize("夜に駆ける").core)
        assertEquals("dont stop me now", TitleNormalizer.normalize("Don’t Stop Me Now").core)
        assertEquals("simon and garfunkel", TitleNormalizer.normalize("Simon & Garfunkel").core)
        assertEquals("hello world", TitleNormalizer.normalize("  Hello   World  ").core)
        assertEquals("fullwidth title", TitleNormalizer.normalize("ＦＵＬＬＷＩＤＴＨ　ＴＩＴＬＥ").core)
    }

    @Test
    fun stripsArtistPrefixOnlyWhenItMatches() {
        assertEquals(
            "one more time",
            TitleNormalizer.normalize("Daft Punk - One More Time", "Daft Punk").core
        )
        assertEquals(
            "daft punk one more time",
            TitleNormalizer.normalize("Daft Punk - One More Time", "Daft Punk Tribute").core
        )
        assertEquals(
            "daft punk one more time",
            TitleNormalizer.normalize("Daft Punk - One More Time").core
        )
        assertEquals(
            "one more time",
            TitleNormalizer.normalize("Daft Punk: One More Time", "Daft Punk").core
        )
    }

    @Test
    fun stripsTrackNumberPrefixesWithoutEatingRealNumbers() {
        assertEquals("one more time", TitleNormalizer.normalize("01 - One More Time").core)
        assertEquals("one more time", TitleNormalizer.normalize("12. One More Time").core)
        assertEquals("1979", TitleNormalizer.normalize("1979").core)
        assertEquals("99 problems", TitleNormalizer.normalize("99 Problems").core)
    }

    @Test
    fun doesNotDiscardTrailingMeaningfulSegment() {
        assertEquals(
            "song part 2 the reckoning",
            TitleNormalizer.normalize("Song - Part 2: The Reckoning").core
        )
        assertFalse(TitleNormalizer.normalize("Song - Part 2").variants.contains("live"))
    }

    @Test
    fun reportsVersion() {
        assertEquals(1, TitleNormalizer.NORMALIZATION_VERSION)
    }
}
