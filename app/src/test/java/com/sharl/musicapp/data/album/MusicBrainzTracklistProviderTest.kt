package com.sharl.musicapp.data.album

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicBrainzTracklistProviderTest {

    private val searchJson = """
        {
          "releases": [
            {
              "id": "rel-compilation",
              "title": "Discovery",
              "artist-credit-phrase": "Daft Punk",
              "primary-type": "Album",
              "secondary-types": ["Compilation"],
              "date": "2001-03-12"
            },
            {
              "id": "rel-official",
              "title": "Discovery",
              "artist-credit-phrase": "Daft Punk",
              "primary-type": "Album",
              "secondary-types": [],
              "date": "2001-03-13",
              "track-count": 14
            },
            {
              "id": "rel-other-artist",
              "title": "Discovery",
              "artist-credit-phrase": "Some Cover Band",
              "primary-type": "Album",
              "secondary-types": [],
              "date": "2001-03-12"
            }
          ]
        }
    """.trimIndent()

    private val releaseJson = """
        {
          "id": "rel-official",
          "title": "Discovery",
          "media": [
            {
              "position": 1,
              "tracks": [
                {
                  "number": "1",
                  "title": "One More Time",
                  "length": 320000,
                  "artist-credit-phrase": "Daft Punk"
                },
                {
                  "number": "2",
                  "title": "Aerodynamic",
                  "length": 212000
                }
              ]
            },
            {
              "position": 2,
              "tracks": [
                {
                  "number": "1",
                  "title": "Bonus Track",
                  "length": 180000
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesReleaseSearchFields() {
        val releases = MusicBrainzTracklistProvider.parseReleaseSearch(searchJson)

        assertEquals(3, releases.size)
        assertEquals("rel-official", releases[1].id)
        assertEquals("Daft Punk", releases[1].artist)
        assertEquals("Album", releases[1].primaryType)
        assertEquals(listOf("Compilation"), releases[0].secondaryTypes)
        assertEquals(14, releases[1].trackCount)
    }

    @Test
    fun selectsOfficialReleaseOverCompilationAndOtherArtist() {
        val releases = MusicBrainzTracklistProvider.parseReleaseSearch(searchJson)

        val selected = MusicBrainzTracklistProvider.selectRelease(releases, "Daft Punk", "Discovery")

        assertEquals("rel-official", selected?.id)
    }

    @Test
    fun refusesToPickBetweenEditionsWithDifferentTrackCounts() {
        val json = """
            {"releases":[
              {"id":"a","title":"Discovery","artist-credit-phrase":"Daft Punk","primary-type":"Album","track-count":14},
              {"id":"b","title":"Discovery","artist-credit-phrase":"Daft Punk","primary-type":"Album","track-count":15}
            ]}
        """.trimIndent()
        val releases = MusicBrainzTracklistProvider.parseReleaseSearch(json)

        assertNull(MusicBrainzTracklistProvider.selectRelease(releases, "Daft Punk", "Discovery"))
    }

    @Test
    fun picksEarliestOfEquivalentEditionsDeterministically() {
        val json = """
            {"releases":[
              {"id":"late","title":"Discovery","artist-credit-phrase":"Daft Punk","primary-type":"Album","track-count":14,"date":"2005"},
              {"id":"early","title":"Discovery","artist-credit-phrase":"Daft Punk","primary-type":"Album","track-count":14,"date":"2001"}
            ]}
        """.trimIndent()
        val releases = MusicBrainzTracklistProvider.parseReleaseSearch(json)

        assertEquals(
            "early",
            MusicBrainzTracklistProvider.selectRelease(releases, "Daft Punk", "Discovery")?.id
        )
    }

    @Test
    fun parsesNestedReleaseGroupAndArtistCredit() {
        val json = """
            {"releases":[{
              "id":"rel","title":"Discovery","date":"2001","track-count":14,
              "artist-credit":[{"name":"Daft Punk","artist":{"id":"x","name":"Daft Punk"}}],
              "release-group":{"primary-type":"Album","secondary-types":["Live"]}
            }]}
        """.trimIndent()
        val release = MusicBrainzTracklistProvider.parseReleaseSearch(json).single()

        assertEquals("Daft Punk", release.artist)
        assertEquals("Album", release.primaryType)
        assertEquals(listOf("Live"), release.secondaryTypes)
    }

    @Test
    fun parsesMultiDiscTracklistInOrder() {
        val tracks = MusicBrainzTracklistProvider.parseReleaseTracks(releaseJson)

        assertEquals(listOf("One More Time", "Aerodynamic", "Bonus Track"), tracks.map { it.title })
        assertEquals(listOf(1, 2, 3), tracks.map { it.trackNumber })
        assertEquals(listOf(320_000L, 212_000L, 180_000L), tracks.map { it.durationMs })
        assertEquals("Daft Punk", tracks[0].artist)
    }

    @Test
    fun fetchesSearchThenReleaseWithUserAgent() = runBlocking {
        val requested = ArrayList<Pair<String, Map<String, String>>>()
        val fetcher = object : HttpTextFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): String? {
                requested.add(url to headers)
                return when {
                    url.contains("/release?query=") -> searchJson
                    url.contains("rel-official?inc=") -> releaseJson
                    else -> null
                }
            }
        }
        val provider = MusicBrainzTracklistProvider(fetcher, "TestAgent/1.0", spacingMs = 0)

        val tracks = provider.tracklist("Daft Punk", "Discovery")

        assertEquals(3, tracks.size)
        assertTrue(requested[0].first.contains("release%3A%22Discovery%22"))
        assertTrue(requested[0].first.contains("artist%3A%22Daft+Punk%22"))
        assertEquals("TestAgent/1.0", requested[0].second["User-Agent"])
        assertTrue(requested[1].first.contains("inc=recordings"))
    }

    @Test
    fun networkFailureYieldsEmptyTracklist() = runBlocking {
        val fetcher = object : HttpTextFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): String? = null
        }
        val provider = MusicBrainzTracklistProvider(fetcher, spacingMs = 0)

        assertTrue(provider.tracklist("Daft Punk", "Discovery").isEmpty())
    }
}
