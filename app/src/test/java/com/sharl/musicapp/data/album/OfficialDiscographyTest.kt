package com.sharl.musicapp.data.album

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialDiscographyTest {

    private val artistJson = """
        {"artists":[
          {"id":"MBID-STROKES","name":"The Strokes","sort-name":"Strokes, The","type":"Group","score":100},
          {"id":"MBID-OTHER","name":"The Stroke","sort-name":"Stroke, The","type":"Person","score":42}
        ]}
    """.trimIndent()

    private val groupsJson = """
        {"release-groups":[
          {"id":"RG1","title":"Is This It","primary-type":"Album","secondary-types":[],
           "first-release-date":"2001-07-30"},
          {"id":"RG2","title":"2000-06-20: King Tut's Wah Wah Hut, Glasgow, UK","primary-type":"Album",
           "secondary-types":["Live"],"first-release-date":"2000-06-20"},
          {"id":"RG3","title":"Singles 1-7","primary-type":"Album","secondary-types":["Compilation"],
           "first-release-date":"2009-11-02"},
          {"id":"RG4","title":"Room on Fire","primary-type":"Album",
           "first-release-date":"2003-11-04"},
          {"id":"RG5","title":"Angles","primary-type":"EP","first-release-date":"2011-03-22"},
          {"id":"RG6","title":"The New Abnormal","primary-type":"Album",
           "secondary-types":["Studio"],"first-release-date":"2020-04-10"},
          {"id":"RG7","title":"Is This It","primary-type":"Album","first-release-date":"2001-08-01"},
          {"id":"RG8","title":"","primary-type":"Album","first-release-date":"1999-01-01"}
        ]}
    """.trimIndent()

    @Test
    fun picksTheArtistWhoseNameActuallyMatches() {
        assertEquals("MBID-STROKES", OfficialDiscography.parseArtistId(artistJson, "The Strokes"))
        assertEquals("MBID-STROKES", OfficialDiscography.parseArtistId(artistJson, "the strokes"))
        assertNull(OfficialDiscography.parseArtistId(artistJson, "New Order"))
        assertNull(OfficialDiscography.parseArtistId(null, "The Strokes"))
        assertNull(OfficialDiscography.parseArtistId("not json", "The Strokes"))
    }

    @Test
    fun keepsOnlyOfficialStudioAlbums() {
        val albums = OfficialDiscography.parseReleaseGroups(groupsJson)
        assertEquals(
            listOf("Is This It", "Room on Fire", "The New Abnormal"),
            albums.map { it.title }
        )
        assertEquals(listOf("2001", "2003", "2020"), albums.map { it.year })
        assertEquals(listOf("RG1", "RG4", "RG6"), albums.map { it.releaseGroupId })
    }

    @Test
    fun providerFetchesArtistThenReleaseGroupsInOrder() = runBlocking {
        val urls = mutableListOf<String>()
        val fetcher = object : HttpTextFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): String? {
                urls += url
                return when {
                    url.contains("/artist?") -> artistJson
                    url.contains("/release-group?") -> groupsJson
                    else -> null
                }
            }
        }
        val albums = MusicBrainzDiscographyProvider(fetcher, spacingMs = 0)
            .studioAlbums("The Strokes", limit = 10)
        assertEquals(listOf("Is This It", "Room on Fire", "The New Abnormal"), albums.map { it.title })
        assertEquals(2, urls.size)
        assertTrue(urls[0].contains("query=The+Strokes") || urls[0].contains("query=The%20Strokes"))
        assertTrue(urls[1].contains("MBID-STROKES"))
        // status:official is what keeps bootleg-only groups (e.g. The Strokes'
        // "Lasting Memory") out of the grid; it cannot be filtered client-side.
        assertTrue(urls[1].contains("status%3Aofficial"))
        assertTrue(urls.all { it.contains("fmt=json") })
    }

    @Test
    fun providerDegradesToEmptyListWithoutNetworkOrBadData() = runBlocking {
        val dead = MusicBrainzDiscographyProvider(
            object : HttpTextFetcher {
                override suspend fun get(url: String, headers: Map<String, String>) = null
            },
            spacingMs = 0
        )
        assertEquals(emptyList<OfficialAlbum>(), dead.studioAlbums("The Strokes"))

        val wrong = MusicBrainzDiscographyProvider(
            object : HttpTextFetcher {
                override suspend fun get(url: String, headers: Map<String, String>) =
                    if (url.contains("/artist?")) """{"artists":[{"id":"X","name":"Someone Else"}]}"""
                    else groupsJson
            },
            spacingMs = 0
        )
        assertEquals(emptyList<OfficialAlbum>(), wrong.studioAlbums("The Strokes"))
    }
}
