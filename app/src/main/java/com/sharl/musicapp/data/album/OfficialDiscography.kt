package com.sharl.musicapp.data.album

import com.sharl.musicapp.data.matching.TitleNormalizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** An official album from a licensed catalog: name, release year and MusicBrainz id. */
data class OfficialAlbum(val title: String, val year: String?, val releaseGroupId: String)

/**
 * Reads an artist's official album list from MusicBrainz. YouTube carries no reliable
 * discography: every "full album" upload for most artists is a fan re-upload, while the
 * release catalog names the real albums outright. Parsers are pure so the exclusions are
 * covered without the network.
 */
object OfficialDiscography {

    private val NOT_ALBUMS = setOf(
        "live", "compilation", "mixtape/playlist", "bootleg", "dj-mix", "soundtrack",
        "broadcast", "speech", "part of", "album pages", "other"
    )

    fun parseArtistId(json: String?, artist: String): String? {
        val target = TitleNormalizer.simple(artist)
        if (json.isNullOrBlank() || target.isBlank()) return null
        val artists = runCatching {
            JSONObject(json.substring(json.indexOf('{'))).optJSONArray("artists") ?: JSONArray()
        }.getOrNull() ?: return null
        val names = (0 until artists.length()).mapNotNull { i ->
            val entry = artists.optJSONObject(i) ?: return@mapNotNull null
            val id = entry.optString("id")
            val name = entry.optString("name")
            if (id.isBlank() || name.isBlank()) null else id to TitleNormalizer.simple(name)
        }
        // Only an exact catalog match is trusted; a similar name is a different artist.
        val dense = target.replace(" ", "")
        return names.firstOrNull { (_, folded) ->
            folded == target || folded.replace(" ", "") == dense
        }?.first
    }

    fun parseReleaseGroups(json: String?): List<OfficialAlbum> {
        if (json.isNullOrBlank()) return emptyList()
        val groups = runCatching {
            JSONObject(json.substring(json.indexOf('{'))).optJSONArray("release-groups") ?: JSONArray()
        }.getOrNull() ?: return emptyList()
        val albums = LinkedHashMap<String, Pair<OfficialAlbum, String>>()
        for (i in 0 until groups.length()) {
            val group = groups.optJSONObject(i) ?: continue
            if (group.optString("primary-type") != "Album") continue
            val secondary = group.optJSONArray("secondary-types") ?: JSONArray()
            val excluded = (0 until secondary.length()).any {
                secondary.optString(it).lowercase(Locale.ROOT) in NOT_ALBUMS
            }
            if (excluded) continue
            val title = group.optString("title").trim()
            val id = group.optString("id")
            if (title.isBlank() || id.isBlank()) continue
            val date = group.optString("first-release-date")
            val album = OfficialAlbum(
                title = title,
                year = date.takeIf { it.length >= 4 }?.substring(0, 4),
                releaseGroupId = id
            )
            val key = TitleNormalizer.simple(title)
            val previous = albums[key]
            // Editions of one album collapse onto the earliest release.
            if (previous == null || date < previous.second) albums[key] = album to date
        }
        return albums.values.sortedBy { it.second }.map { it.first }
    }
}

class MusicBrainzDiscographyProvider(
    private val fetcher: HttpTextFetcher,
    private val userAgent: String = "MusicApp/1.0 (artist-discography)",
    private val spacingMs: Long = 1_100L,
    private val log: (String) -> Unit = {}
) {
    private var lastRequestAt = 0L

    suspend fun studioAlbums(artist: String, limit: Int = 20): List<OfficialAlbum> {
        if (artist.isBlank()) return emptyList()
        val encoded = URLEncoder.encode(artist, "UTF-8")
        val artistJson = request("$BASE/artist?query=$encoded&fmt=json&limit=$ARTIST_LIMIT")
        if (artistJson == null) {
            log("artist lookup failed for '$artist'")
            return emptyList()
        }
        val mbid = OfficialDiscography.parseArtistId(artistJson, artist)
        if (mbid == null) {
            log("no catalog artist matched '$artist'")
            return emptyList()
        }
        val query = URLEncoder.encode("arid:$mbid AND type:album AND status:official", "UTF-8")
        val groups = request("$BASE/release-group?query=$query&fmt=json&limit=$GROUP_LIMIT")
        if (groups == null) {
            log("release-group request failed for $mbid")
            return emptyList()
        }
        val albums = OfficialDiscography.parseReleaseGroups(groups).take(limit)
        log("$mbid -> ${albums.map { it.title }}")
        return albums
    }

    private suspend fun request(url: String): String? {
        val now = System.currentTimeMillis()
        if (lastRequestAt > 0 && spacingMs > now - lastRequestAt) {
            delay(spacingMs - (now - lastRequestAt))
        }
        lastRequestAt = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            runCatching {
                fetcher.get(url, mapOf("User-Agent" to userAgent, "Accept" to "application/json"))
            }.getOrNull()
        }
    }

    private companion object {
        const val BASE = "https://musicbrainz.org/ws/2"
        const val ARTIST_LIMIT = 5
        const val GROUP_LIMIT = 100
    }
}
