package com.sharl.musicapp.data.album

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefensiveAlbumResolverTest {

    private val discovery = AlbumResolutionSource.OFFICIAL_PLAYLIST

    private class FakeCatalog(
        var cards: List<AlbumCard> = emptyList(),
        var listings: Map<String, AlbumListing> = emptyMap(),
        var songResults: (String) -> List<AlbumCandidate> = { emptyList() },
        var youtubeResults: (String) -> List<AlbumCandidate> = { emptyList() },
        var cardsFailure: Boolean = false,
        var songFailure: Boolean = false
    ) : AlbumCatalog {
        var cardSearches = 0
        var listingLoads = 0
        var songSearches = 0

        override suspend fun searchAlbumCards(query: String): List<AlbumCard> {
            cardSearches++
            if (cardsFailure) throw IllegalStateException("network down")
            return cards
        }

        override suspend fun loadAlbumPlaylist(browseId: String): AlbumListing? {
            listingLoads++
            return listings[browseId]
        }

        override suspend fun searchSongCandidates(query: String, limit: Int): List<AlbumCandidate> {
            songSearches++
            if (songFailure) throw IllegalStateException("song search down")
            return songResults(query)
        }

        override suspend fun searchYouTubeCandidates(query: String, limit: Int): List<AlbumCandidate> =
            youtubeResults(query)
    }

    private fun candidate(
        id: String,
        title: String,
        artist: String? = null,
        duration: Long? = null,
        song: Boolean = false,
        track: Int? = null
    ) = AlbumCandidate(id, title, artist, duration, song, trackNumber = track)

    @Test
    fun officialPlaylistKeepsPerTrackArtistsAndDoesNotInventUnknownArtists() = runBlocking {
        val catalog = FakeCatalog(listings = mapOf(
            "OLAK5uy_test" to AlbumListing("Compilation", listOf(
                candidate("a", "Intro", "Artist A - Topic", 60_000),
                candidate("b", "Intro", "Artist B - Topic", 61_000),
                candidate("c", "Unknown", null, 62_000)
            ))
        ))
        val result = DefensiveAlbumResolver(catalog).resolve("Screen Artist", "OLAK5uy_test")
        assertEquals(listOf("Artist A", "Artist B", null), result.tracks.map { it.artist })
        assertEquals(listOf("a", "b", "c"), result.tracks.map { it.videoId })
    }

    @Test
    fun officialPlaylistWinsAndKeepsSameTitledTracksInOrder() = runBlocking {
        val catalog = FakeCatalog(
            cards = listOf(AlbumCard("MPREb_discovery", "Discovery")),
            listings = mapOf(
                "MPREb_discovery" to AlbumListing(
                    "Discovery",
                    listOf(
                        candidate("t1", "One More Time", duration = 320_000, track = 1),
                        candidate("t2", "Aerodynamic", duration = 212_000, track = 2),
                        // Two distinct tracks that normalize to a similar title must both survive.
                        candidate("t3a", "Nightvision", duration = 130_000, track = 3),
                        candidate("t3b", "Nightvision (Reprise)", duration = 95_000, track = 4),
                        candidate("bad", "Discovery (Full Album)", duration = 3_600_000, track = 5)
                    )
                )
            )
        )
        val resolver = DefensiveAlbumResolver(catalog, searchSpacingMs = 0)

        val result = resolver.resolve("Daft Punk", "Discovery", 20)

        assertEquals(discovery, result.source)
        assertEquals(listOf("t1", "t2", "t3a", "t3b"), result.tracks.map { it.videoId })
        assertEquals(listOf(1, 2, 3, 4), result.tracks.map { it.trackNumber })
        assertTrue(result.warnings.any { it.contains("Dropped 1 invalid") })
    }

    @Test
    fun officialMismatchFallsBackToTracklistWithHighConfidenceMatches() = runBlocking {
        val catalog = FakeCatalog(
            cards = listOf(AlbumCard("MPREb_wrong", "Discovery")),
            listings = mapOf(
                "MPREb_wrong" to AlbumListing(
                    "Homework",
                    listOf(candidate("x", "Da Funk", duration = 330_000, track = 1))
                )
            ),
            youtubeResults = { query ->
                when {
                    query.contains("One More Time") -> listOf(
                        candidate("m1", "One More Time", "Daft Punk - Topic", 321_000, song = true)
                    )
                    query.contains("Aerodynamic") -> listOf(
                        candidate("m2", "Aerodynamic", "Daft Punk - Topic", 211_000, song = true)
                    )
                    else -> emptyList()
                }
            }
        )
        val tracklist = object : TracklistProvider {
            override suspend fun tracklist(artist: String, albumTitle: String): List<AlbumTarget> {
                assertEquals("Daft Punk", artist)
                assertEquals("Discovery", albumTitle)
                return listOf(
                    AlbumTarget("One More Time", "Daft Punk", 320_000, 1),
                    AlbumTarget("Aerodynamic", "Daft Punk", 212_000, 2)
                )
            }
        }
        val resolver = DefensiveAlbumResolver(catalog, tracklist, searchSpacingMs = 0)

        val result = resolver.resolve("Daft Punk", "Discovery", 20)

        assertEquals(AlbumResolutionSource.TRACKLIST_MATCHED, result.source)
        assertEquals(listOf("m1", "m2"), result.tracks.map { it.videoId })
        assertEquals(listOf(1, 2), result.tracks.map { it.trackNumber })
        assertTrue(result.needsReview.isEmpty())
    }

    @Test
    fun ambiguousCandidateIsNotAutoSelectedAndIsReportedForReview() = runBlocking {
        val catalog = FakeCatalog(
            cards = emptyList(),
            youtubeResults = { query ->
                if (query.contains("Aerodynamic")) {
                    listOf(candidate("m2", "Aerodynamic", "Daft Punk - Topic", 211_000, song = true))
                } else {
                    listOf(
                        candidate("b1", "Around the World", duration = null),
                        candidate("b2", "Around the World", duration = null)
                    )
                }
            }
        )
        val tracklist = object : TracklistProvider {
            override suspend fun tracklist(artist: String, albumTitle: String) = listOf(
                AlbumTarget("Aerodynamic", "Daft Punk", 212_000, 1),
                AlbumTarget("Around the World", "Daft Punk", null, 2)
            )
        }
        val resolver = DefensiveAlbumResolver(catalog, tracklist, searchSpacingMs = 0)

        val result = resolver.resolve("Daft Punk", "Homework", 20)

        assertEquals(AlbumResolutionSource.TRACKLIST_MATCHED, result.source)
        assertEquals(listOf("m2"), result.tracks.map { it.videoId })
        assertEquals(listOf("Around the World"), result.needsReview)
        assertTrue(result.warnings.any { it.contains("Needs review") })
    }

    @Test
    fun successfulResolutionIsCachedUntilTtlExpires() = runBlocking {
        var now = 1_000L
        val catalog = FakeCatalog(
            cards = listOf(AlbumCard("MPREb_discovery", "Discovery")),
            listings = mapOf(
                "MPREb_discovery" to AlbumListing(
                    "Discovery",
                    listOf(candidate("t1", "One More Time", duration = 320_000, track = 1))
                )
            )
        )
        val resolver = DefensiveAlbumResolver(
            catalog = catalog,
            clock = { now },
            searchSpacingMs = 0,
            cacheTtlMs = 1_000
        )

        resolver.resolve("Daft Punk", "Discovery")
        resolver.resolve("Daft Punk", "Discovery")
        assertEquals(1, catalog.cardSearches)

        now += 1_001
        resolver.resolve("Daft Punk", "Discovery")
        assertEquals(2, catalog.cardSearches)
    }

    @Test
    fun failsOverToSearchFallbackWithNormalizedDedupe() = runBlocking {
        val catalog = FakeCatalog(
            cardsFailure = true,
            youtubeResults = {
                listOf(
                    candidate("s1", "Aerodynamic", "Daft Punk", duration = 212_000),
                    candidate("s2", "Aerodynamic (Official Audio)", "Daft Punk - Topic", duration = 213_000),
                    candidate("s3", "Discovery (Full Album)", duration = 3_600_000),
                    candidate("s4", "Too Short", duration = 5_000)
                )
            }
        )
        val resolver = DefensiveAlbumResolver(catalog, searchSpacingMs = 0)

        val result = resolver.resolve("Daft Punk", "Discovery", 20)

        assertEquals(AlbumResolutionSource.SEARCH_FALLBACK, result.source)
        assertEquals(listOf("s1"), result.tracks.map { it.videoId })
        assertTrue(result.warnings.any { it.contains("album search failed") })
    }

    @Test
    fun directIdsBypassNameSearchAndKeepOfficialDuplicates() = runBlocking {
        for (id in listOf("OLAK5uy_example", "MPREb_example")) {
            val catalog = FakeCatalog(listings = mapOf(id to AlbumListing("Actual Album", listOf(
                candidate("a", "Same", "Artist", 200_000),
                candidate("b", "Same", "Artist", 200_000),
                candidate("a", "Same", "Artist", 200_000)
            ))))
            val result = DefensiveAlbumResolver(catalog, searchSpacingMs = 0).resolve("Artist", id)
            assertEquals(discovery, result.source)
            assertEquals("Actual Album", result.albumTitle)
            assertEquals(listOf("a", "b"), result.tracks.map { it.videoId })
            assertEquals(0, catalog.cardSearches)
        }
    }

    @Test
    fun fallbackRequiresArtistDurationAndConservativeVariantIdentity() = runBlocking {
        val rows = listOf(
            candidate("a", "Song", "Artist", 200_000),
            candidate("boundary", " song (Official Audio) ", "ARTIST - Topic", 203_000),
            candidate("outside", "Song", "Artist", 203_001),
            candidate("other", "Song", "Other", 200_000),
            candidate("live", "Song (Live)", "Artist", 200_000),
            candidate("unknown", "Song (Mystery Edition)", "Artist", 200_000),
            candidate("u1", "Song", "Artist"),
            candidate("u2", "Song", "Artist"),
            candidate("zero", "Song", "Artist", 0),
            candidate("missingArtist", "Song", duration = 200_000),
            candidate("a", "Renamed", "Other", 400_000)
        )
        val resolver = DefensiveAlbumResolver(FakeCatalog(youtubeResults = { rows }), searchSpacingMs = 0)
        assertEquals(listOf("a", "outside", "other", "live", "unknown", "u1", "u2", "zero", "missingArtist"),
            resolver.resolve("Artist", "Album").tracks.map { it.videoId })
    }

    @Test
    fun cancellationIsNeverConvertedToFallback() = runBlocking {
        val catalog = FakeCatalog(youtubeResults = { throw kotlinx.coroutines.CancellationException("stop") })
        try {
            DefensiveAlbumResolver(catalog, searchSpacingMs = 0).resolve("Artist", "Album")
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertEquals("stop", expected.message)
        }
    }

    @Test
    fun missingDirectIdDoesNotSearchForIdAsAlbumName() = runBlocking {
        val catalog = FakeCatalog(youtubeResults = { listOf(candidate("wrong", "Unrelated")) })
        val result = DefensiveAlbumResolver(catalog, searchSpacingMs = 0).resolve("Artist", "OLAK5uy_missing")
        assertTrue(result.tracks.isEmpty())
        assertEquals(0, catalog.cardSearches)
    }

    @Test
    fun cancellationPropagatesAcrossEveryCatalogStage() = runBlocking {
        for (stage in listOf("cards", "playlist", "songs")) {
            val fake = FakeCatalog()
            val catalog = object : AlbumCatalog by fake {
                override suspend fun searchAlbumCards(query: String): List<AlbumCard> {
                    if (stage == "cards") throw kotlinx.coroutines.CancellationException(stage)
                    return if (stage == "playlist") listOf(AlbumCard("MPREb_test", "Album")) else emptyList()
                }
                override suspend fun loadAlbumPlaylist(browseId: String): AlbumListing? {
                    throw kotlinx.coroutines.CancellationException(stage)
                }
                override suspend fun searchSongCandidates(query: String, limit: Int): List<AlbumCandidate> {
                    throw kotlinx.coroutines.CancellationException(stage)
                }
            }
            try {
                DefensiveAlbumResolver(catalog, searchSpacingMs = 0).resolve("Artist", "Album")
                org.junit.Assert.fail("Cancellation swallowed at $stage")
            } catch (expected: kotlinx.coroutines.CancellationException) {
                assertEquals(stage, expected.message)
            }
        }
    }

    @Test
    fun failedDirectLookupCanBeRetriedAndIdCacheIsCaseSensitive() = runBlocking {
        val catalog = FakeCatalog()
        val resolver = DefensiveAlbumResolver(catalog, searchSpacingMs = 0)
        assertTrue(resolver.resolve("Artist", "OLAK5uy_Test").tracks.isEmpty())
        catalog.listings = mapOf(
            "OLAK5uy_Test" to AlbumListing("Upper", listOf(candidate("a", "Song"))),
            "OLAK5uy_test" to AlbumListing("Lower", listOf(candidate("b", "Song")))
        )
        assertEquals("Upper", resolver.resolve("Artist", "OLAK5uy_Test").albumTitle)
        assertEquals("Lower", resolver.resolve("Artist", "OLAK5uy_test").albumTitle)
    }

    @Test
    fun suppliedUrlsAreRejectedWithoutCallingCatalog() = runBlocking {
        val catalog = FakeCatalog()
        val result = DefensiveAlbumResolver(catalog, searchSpacingMs = 0)
            .resolve("Artist", "https://evil.example/playlist?list=OLAK5uy_test")
        assertTrue(result.tracks.isEmpty())
        assertEquals(0, catalog.cardSearches)
        assertEquals(0, catalog.listingLoads)
    }

    @Test
    fun playlistUrlsAreBuiltOnlyFromBareAllowlistedIds() {
        assertEquals("https://music.youtube.com/playlist?list=OLAK5uy_test", AlbumInput.playlistUrl("OLAK5uy_test"))
        assertEquals("https://music.youtube.com/browse/MPREb_test", AlbumInput.playlistUrl("MPREb_test"))
        for (input in listOf("https://evil.example/OLAK5uy_test", "https://music.youtube.com/playlist?list=OLAK5uy_test",
            "OLAK5uy_test&list=evil", "MPREb_test/../../evil", "PL_arbitrary", "MPREb", "")) {
            assertEquals(null, AlbumInput.playlistUrl(input))
        }
    }

    @Test
    fun musicBrainzCancellationPropagates() = runBlocking {
        val provider = MusicBrainzTracklistProvider(object : HttpTextFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): String? {
                throw kotlinx.coroutines.CancellationException("cancel MB")
            }
        }, spacingMs = 0)
        try {
            DefensiveAlbumResolver(FakeCatalog(), provider, searchSpacingMs = 0).resolve("Artist", "Album")
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (expected: kotlinx.coroutines.CancellationException) {
            assertEquals("cancel MB", expected.message)
        }
    }

    @Test
    fun resolvedMetadataIsCleanWithoutRemovingMeaningfulBrackets() = runBlocking {
        val catalog = FakeCatalog(youtubeResults = { listOf(
            candidate("a", "  Song (Official Audio)  ", "Artist - Topic", 200_000),
            candidate("b", "Song (Live at Home)", "Artist", 200_000),
            candidate("c", "Song (Live at Home)", "Artist", 200_001)
        ) })
        val tracks = DefensiveAlbumResolver(catalog, searchSpacingMs = 0).resolve("Artist", "Album").tracks
        assertEquals(listOf("Song", "Song (Live at Home)", "Song (Live at Home)"), tracks.map { it.title })
        assertEquals(listOf("Artist", "Artist", "Artist"), tracks.map { it.artist })
    }

    @Test
    fun unbracketedLiveAndAmbiguousVersionsAreNotCollapsed() = runBlocking {
        val titles = listOf("Song live at Home", "Song - Mystery Edition", "Song alternate take")
        val rows = titles.flatMapIndexed { index, title -> listOf(
            candidate("${index}a", title, "Artist", 200_000),
            candidate("${index}b", title, "Artist", 200_001)
        ) }
        val result = DefensiveAlbumResolver(FakeCatalog(youtubeResults = { rows }), searchSpacingMs = 0)
            .resolve("Artist", "Album")
        assertEquals(rows.map { it.videoId }, result.tracks.map { it.videoId })
    }

    @Test
    fun catalogFailuresDegradeToEmptyResolution() = runBlocking {
        val catalog = FakeCatalog(cardsFailure = true, songFailure = true)
        val resolver = DefensiveAlbumResolver(catalog, searchSpacingMs = 0)

        val result = resolver.resolve("Nobody", "Nothing", 20)

        assertEquals(AlbumResolutionSource.NONE, result.source)
        assertTrue(result.tracks.isEmpty())
        assertTrue(result.warnings.isNotEmpty())
    }
}
