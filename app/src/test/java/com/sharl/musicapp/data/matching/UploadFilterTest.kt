package com.sharl.musicapp.data.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadFilterTest {

    private val strokes = "The Strokes"

    private fun trust(channel: String?, artist: String = strokes) = UploadFilter.trust(artist, channel)

    private fun reason(
        channel: String?,
        title: String,
        durationMs: Long? = 1_000_000,
        album: Boolean = true,
        strict: Boolean = true
    ) = UploadFilter.rejectionReason(strokes, channel, title, durationMs, albumContext = album, strictChannel = strict)

    @Test
    fun goldStandardIsTheTopicSuffix() {
        assertEquals(ChannelTrust.TOPIC, trust("The Strokes - Topic"))
        assertEquals(ChannelTrust.TOPIC, trust("RCA Records - Topic"))
        // A channel that merely mentions Topic mid-name is not a label catalog.
        assertEquals(ChannelTrust.UNTRUSTED, trust("Topic Talk Music"))
    }

    @Test
    fun vevoSuffixIsTrusted() {
        assertEquals(ChannelTrust.VEVO, trust("TheStrokesVEVO"))
        assertEquals(ChannelTrust.VEVO, trust("Legacy Recordings VEVO"))
        assertEquals(ChannelTrust.UNTRUSTED, trust("VEVO Reactions Daily"))
    }

    @Test
    fun artistChannelNeedsEqualityNotContainment() {
        assertEquals(ChannelTrust.ARTIST, trust("The Strokes"))
        assertEquals(ChannelTrust.ARTIST, trust("the  strokes"))
        // Phase 51 accepted these by containment; they are fan channels.
        assertEquals(ChannelTrust.UNTRUSTED, trust("Nameless MADtv Channel", "mad tv"))
        assertEquals(ChannelTrust.UNTRUSTED, trust("Best Of The Strokes HQ"))
        assertEquals(ChannelTrust.UNTRUSTED, trust("Music Mixes"))
        // Spacing inside the name is significant, so a comedy troupe whose channel is
        // "MADtv" is not the artist the user searched for as "mad tv".
        assertEquals(ChannelTrust.UNTRUSTED, trust("MADtv", "mad tv"))
        assertEquals(ChannelTrust.UNTRUSTED, trust("The1975", "The 1975"))
    }

    @Test
    fun untrustedChannelIsRejectedEvenWithAPerfectTitle() {
        assertEquals("channel", reason("Guitar Tabs 101", "Is This It")?.substringBefore(":"))
        assertEquals("channel", reason("Indie Vibes", "Is This It")?.substringBefore(":"))
        assertEquals("channel", reason("Mad TV", "Is This It")?.substringBefore(":"))
        assertNotNull(reason(null, "Is This It"))
    }

    @Test
    fun nonMusicWordsRevokeUntrustedChannelsInTheFallback() {
        val junk = listOf(
            "Guitar", "tab", "tutorial", "Cover", "repost", "Instrumental", "karaoke",
            "reaction", "tiktok", "shorts", "mashup", "remix", "slowed", "reverb", "8d audio"
        )
        junk.forEach { word ->
            val title = "Is This It ($word)"
            // Strict mode never admits an untrusted channel at all.
            assertEquals("channel", reason("Indie Vibes", title)?.substringBefore(":"))
            // The fallback admits it only when the title is not junk.
            assertTrue(
                "expected '$title' rejected from an untrusted channel in fallback",
                reason("Indie Vibes", title, strict = false)?.startsWith("title") == true
            )
            assertNull("expected '$title' kept on the artist channel", reason(strokes, title))
            assertNull("expected '$title' kept on a Topic channel", reason("The Strokes - Topic", title))
            assertNull(
                "expected '$title' kept on an untrusted channel in fallback when it is clean",
                reason("Indie Vibes", "Is This It", strict = false)
            )
        }
    }

    @Test
    fun realSongsSurviveOnTheirOwnChannel() {
        assertNull(reason(strokes, "Under Cover of Darkness (Official Video)"))
        assertNull(reason("The Beatles - Topic", "While My Guitar Gently Weeps"))
        assertNull(reason(strokes, "Crosseyed Angelfish (Remix)"))
    }

    @Test
    fun compilationPhrasesAreRejectedEvenOnTrustedChannels() {
        listOf(
            "The Strokes Best Of", "Greatest Hits", "Compilation", "Is This It Full Album",
            "Top 10", "Top 20", "All Songs", "Live at the Hollywood Bowl", "Concert Film",
            "Lyrics", "Mix", "Playlist", "Livestream", "Podcast", "Mix Tape"
        ).forEach { title ->
            assertNotNull("expected '$title' rejected on the artist channel", reason(strokes, title))
        }
    }

    @Test
    fun albumDurationCeilingAppliesToEveryChannel() {
        assertNotNull(reason(strokes, "Is This It", 3_600_001))
        assertNotNull(reason("The Strokes - Topic", "Is This It", 3_600_001))
        assertNull(reason(strokes, "Is This It", 3_600_000))
        assertNull(reason(strokes, "Is This It", null))
    }

    @Test
    fun trackSearchIgnoresTheAlbumRules() {
        // A three-minute single is not an album claim: neither the 60 minute ceiling
        // nor the package-shape words may fire.
        assertNull(reason(strokes, "Is This It", 3_600_001, album = false))
        assertNull(reason(strokes, "Someday", 184_000, album = false))
        assertNull(reason(strokes, "Greatest Hits", 184_000, album = false))
        assertNull(reason(strokes, "Song (Live at Home)", 184_000, album = false))
        // The non-music blocklist still polices untrusted channels in track search.
        assertEquals("title", reason("Indie Vibes", "Is This It (Cover)", 184_000, album = false, strict = false)
            ?.substringBefore(":"))
    }

    /** Fixtures taken verbatim from `ytsearch20:The Strokes full album` on device. */
    @Test
    fun everyRealFanUploadFromTheProbeIsRejected() {
        listOf(
            "Irish O'African" to "T̲he S̲t̲rokes - I̲s T̲h̲is I̲t (Full Album)",
            "Rock Classic" to "The Strokes Best Songs - The Strokes Best Of - Best of The Strokes Collection 2022",
            "kevin juncal" to "The Strokes - The New Abnormal (2020) (Full Album) HQ",
            "Hard Rock" to "Best The Strokes  Songs - The Strokes Greatest Hits - The Strokes Full Album 2022",
            "مرح TV" to "The Strokes 2025 MIX Best Collection - The Adults Are Talking, Reptilia",
            "Jorge Medina" to "The Strokes - Lost Treasures (Full Album)",
            "rivertine" to "The Strokes - The New Abnormal (Full Album)",
            "Camilo 8" to "Future Present Past-The Strokes",
            "Business is Business" to "The Strokes - Reality Awaits (FULL ALBUM)",
            "bri5a_" to "The Strokes - The New Abnormal (full album) (2)",
            "santiago tellez" to "First Impressions of Earth | Album (432Hz)",
            "WHIPLASH" to "Th͟e͟ ͟Strok͟e͟s͟ ‎- Roo͟m͟ ͟On Fir͟e͟ ͟( Album Full 2003)",
            "acevassos" to "Brutally Ranking Every Strokes Album (Including Reality Awaits)",
            "thenyoutrust" to "The Strokes - Chill Songs Playlist",
            "Red Rock" to "The Strokes  Best Songs - The Strokes  Greatest Hits - The Strokes  Full Album"
        ).forEach { (channel, title) ->
            assertEquals("expected '$channel' rejected", "channel",
                reason(channel, title)?.substringBefore(":"))
        }
    }

    @Test
    fun trustedChannelsSortFirst() {
        assertEquals(0, UploadFilter.priority(ChannelTrust.TOPIC))
        assertEquals(1, UploadFilter.priority(ChannelTrust.VEVO))
        assertEquals(2, UploadFilter.priority(ChannelTrust.ARTIST))
        assertEquals(3, UploadFilter.priority(ChannelTrust.UNTRUSTED))
        assertTrue(UploadFilter.priority(ChannelTrust.TOPIC) < UploadFilter.priority(ChannelTrust.ARTIST))
    }
}
