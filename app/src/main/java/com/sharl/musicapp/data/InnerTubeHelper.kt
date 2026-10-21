package com.sharl.musicapp.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class RelatedVideo(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String?,
    // Only some search layouts expose this; null means "unknown".
    val subscribers: Long? = null,
    // Parsed from lengthText / thumbnail duration badge; null means "unknown".
    val durationMs: Long? = null
)

/** Both resolvers failed: surfaced verbatim so the Snackbar names the cause. */
class StreamingBlockedException :
    java.io.IOException("Streaming failed: YouTube API blocked.")

object InnerTubeHelper {
    // NOTE TO DEV: These constants are what YouTube changes. If InnerTube starts failing,
    // update the clientVersion or User-Agent here and push an OTA update.
    private const val CLIENT_VERSION = "1.56.21"
    private const val USER_AGENT = "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12; en_US; Quest 3) gzip"
    private const val API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8" // Standard web key, usually works for VR client

    // ANDROID_VR has been answering LOGIN_REQUIRED ("confirm you're not a bot")
    // since 2026-10, so the same endpoint is retried with the iOS client, which
    // still hands out direct audio URLs.
    private const val IOS_CLIENT_VERSION = "20.10.4"
    private const val IOS_USER_AGENT =
        "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    // YouTube now expects a visitor id on player requests; without one some
    // clients answer LOGIN_REQUIRED ("confirm you're not a bot"). Fetched once
    // from the visitor_id endpoint and reused for every player call.
    @Volatile
    private var visitorData: String? = null

    suspend fun getStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        ensureVisitorData()
        val url = fetchBestAudioUrl(videoId, "IOS", IOS_CLIENT_VERSION, IOS_USER_AGENT)
            ?: fetchBestAudioUrl(videoId, "ANDROID_VR", CLIENT_VERSION, USER_AGENT)
        Log.d(
            "InnerTube",
            "getStreamUrl($videoId) -> ${if (url != null) "ok" else "failed"} " +
                "in ${System.currentTimeMillis() - started}ms"
        )
        url
    }

    private fun ensureVisitorData(): String? {
        visitorData?.let { return it }
        synchronized(this) {
            visitorData?.let { return it }
            try {
                val conn = (URL("https://www.youtube.com/youtubei/v1/visitor_id?key=$API_KEY")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("User-Agent", IOS_USER_AGENT)
                    connectTimeout = 5000
                    readTimeout = 5000
                    doOutput = true
                }
                val body = JSONObject()
                    .put(
                        "context",
                        JSONObject().put(
                            "client",
                            JSONObject()
                                .put("clientName", "IOS")
                                .put("clientVersion", IOS_CLIENT_VERSION)
                        )
                    )
                    .toString()
                conn.outputStream.write(body.toByteArray())
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    visitorData = json.optJSONObject("responseContext")
                        ?.optString("visitorData")
                        ?.takeIf { it.isNotBlank() }
                    Log.d("InnerTube", "visitorData ${if (visitorData != null) "acquired" else "missing"}")
                } else {
                    Log.w("InnerTube", "visitor_id HTTP ${conn.responseCode}")
                }
            } catch (e: Exception) {
                Log.w("InnerTube", "visitor_id failed: ${e.message}")
            }
            return visitorData
        }
    }

    private fun fetchBestAudioUrl(
        videoId: String,
        clientName: String,
        clientVersion: String,
        userAgent: String
    ): String? {
        try {
            val url = URL("https://www.youtube.com/youtubei/v1/player?key=$API_KEY")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 5000
            conn.readTimeout = 5000

            val client = JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                if (clientName == "IOS") {
                    put("deviceMake", "Apple")
                    put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone")
                    put("osVersion", "18.3.2.22D82")
                } else {
                    put("androidSdkVersion", 32)
                    put("osName", "Android")
                    put("osVersion", "12")
                }
                visitorData?.let { put("visitorData", it) }
            }
            val jsonBody = JSONObject()
                .put("context", JSONObject().put("client", client))
                .put("videoId", videoId)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true)
                .put(
                    "playbackContext",
                    JSONObject().put(
                        "contentPlaybackContext",
                        JSONObject().put("html5Preference", "HTML5_PREF_WANTS")
                    )
                )
                .toString()

            conn.doOutput = true
            conn.outputStream.write(jsonBody.toByteArray())

            if (conn.responseCode != 200) {
                Log.e("InnerTube", "$clientName HTTP ${conn.responseCode} for $videoId")
                return null
            }
            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)
            json.optJSONObject("responseContext")
                ?.optString("visitorData")
                ?.takeIf { it.isNotBlank() }
                ?.let { visitorData = it }

            val playability = json.optJSONObject("playabilityStatus")
            val status = playability?.optString("status").orEmpty()
            if (status.isNotEmpty() && status != "OK") {
                Log.w(
                    "InnerTube",
                    "$clientName playability=$status reason=${playability?.optString("reason")} ($videoId)"
                )
            }
            val streamingData = json.optJSONObject("streamingData")
            val formats = streamingData?.optJSONArray("adaptiveFormats")
            if (formats == null) {
                Log.e(
                    "InnerTube",
                    "$clientName returned no adaptiveFormats (status=${status.ifBlank { "?" }}) for $videoId"
                )
                return null
            }

            // Find highest bitrate audio (m4a/webm), accepting both direct URLs
            // and signatureCipher entries (some clients stopped sending "url").
            var bestUrl: String? = null
            var maxBitrate = 0
            for (i in 0 until formats.length()) {
                val format = formats.getJSONObject(i)
                val mime = format.optString("mimeType", "")
                if (!mime.contains("audio")) continue
                val bitrate = format.optInt("bitrate", 0)
                val url = formatUrl(format)
                if (url.isNotEmpty() && bitrate > maxBitrate) {
                    maxBitrate = bitrate
                    bestUrl = url
                }
            }
            if (bestUrl == null) {
                Log.e("InnerTube", "$clientName had ${formats.length()} formats but no playable audio ($videoId)")
            }
            return bestUrl
        } catch (e: Exception) {
            Log.e("InnerTube", "Fast extraction failed ($clientName)", e)
        }
        return null
    }

    private fun formatUrl(format: JSONObject): String {
        val direct = format.optString("url", "")
        if (direct.isNotEmpty()) return direct
        val cipher = format.optString("signatureCipher", "")
            .ifBlank { format.optString("cipher", "") }
        if (cipher.isBlank()) return ""
        return try {
            val params = android.net.Uri.parse("https://x/?$cipher")
            val base = params.getQueryParameter("url") ?: return ""
            val signature = params.getQueryParameter("s") ?: return ""
            val key = params.getQueryParameter("sp") ?: "signature"
            val separator = if (base.contains("?")) "&" else "?"
            "$base$separator$key=$signature"
        } catch (e: Exception) {
            ""
        }
    }

    // Related videos for the Mix engine. The WEB client answers with the modern
    // lockupViewModel layout, ANDROID_VR with compactVideoRenderer; both lists are
    // merged (they overlap heavily) to squeeze out as many unique tracks as possible.
    suspend fun getRelatedVideos(videoId: String): List<RelatedVideo> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, RelatedVideo>()
            fetchNext(videoId, WEB_CONTEXT, "Mozilla/5.0")?.let { json ->
                parseWebRelated(json).forEach { out.putIfAbsent(it.videoId, it) }
            }
            fetchNext(videoId, VR_CONTEXT, USER_AGENT)?.let { json ->
                parseVrRelated(json).forEach { out.putIfAbsent(it.videoId, it) }
            }
            // Phase 23: mixes must stay single-song sized. Known durations
            // outside 30s..15min and junk titles are dropped here so every
            // consumer (Mix, Discover) gets a clean list.
            out.values.filter { !isJunkTrack(it.title, it.durationMs) }
        }

    // ---- Track sanity filter -------------------------------------------------

    const val MIN_TRACK_MS = 30_000L
    const val MAX_TRACK_MS = 15L * 60 * 1000

    // Word-boundary matching so "Remix", "Deliver" or "Living" don't trip the
    // "mix"/"live" filters while "Nightcore Mix", "Live at …" and "1 hour" do.
    private val JUNK_TITLE = Regex(
        "\\b(lyrics?|live|mix|hours?|compilations?|reactions?|tutorials?|podcasts?|" +
            "playlist|full album|livestream|interview|audition|best of|best songs|" +
            "greatest hits|top songs|behind the scenes|trailer|vlog|prank|x factor|got talent)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isJunkTrack(title: String, durationMs: Long?): Boolean {
        if (durationMs != null && durationMs !in MIN_TRACK_MS..MAX_TRACK_MS) return true
        return JUNK_TITLE.containsMatchIn(title)
    }

    // "4:25" / "1:02:33" -> milliseconds; null when the text isn't a timestamp.
    fun parseDurationMs(text: String): Long? {
        val parts = text.trim().split(":")
        if (parts.size !in 2..3) return null
        val nums = parts.map { it.trim().toLongOrNull() ?: return null }
        val seconds = when (nums.size) {
            2 -> nums[0] * 60 + nums[1]
            else -> nums[0] * 3600 + nums[1] * 60 + nums[2]
        }
        return seconds * 1000L
    }

    // WEB lockups carry the duration in a thumbnail badge ("44:08").
    private fun lockupDurationMs(lockup: JSONObject): Long? {
        val overlays = lockup.optJSONObject("contentImage")
            ?.optJSONObject("thumbnailViewModel")
            ?.optJSONArray("overlays") ?: return null
        for (i in 0 until overlays.length()) {
            val overlay = overlays.optJSONObject(i) ?: continue
            val keys = overlay.keys()
            while (keys.hasNext()) {
                val renderer = overlay.optJSONObject(keys.next()) ?: continue
                val badges = renderer.optJSONArray("badges") ?: continue
                for (b in 0 until badges.length()) {
                    val badge = badges.optJSONObject(b)
                        ?.optJSONObject("thumbnailBadgeViewModel") ?: continue
                    parseDurationMs(badge.optString("text"))?.let { return it }
                }
            }
        }
        return null
    }

    private val WEB_CONTEXT =
        "\"clientName\":\"WEB\",\"clientVersion\":\"2.20250101.00.00\""
    private val VR_CONTEXT =
        "\"clientName\":\"ANDROID_VR\",\"clientVersion\":\"$CLIENT_VERSION\"," +
            "\"androidSdkVersion\":32,\"osName\":\"Android\",\"osVersion\":\"12\""

    private fun fetchNext(videoId: String, clientJson: String, userAgent: String): JSONObject? {
        return try {
            val url = URL("https://www.youtube.com/youtubei/v1/next?key=$API_KEY")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", userAgent)
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.doOutput = true
            val body = """{"context":{"client":{$clientJson}},"videoId":"$videoId"}"""
            conn.outputStream.write(body.toByteArray())
            if (conn.responseCode == 200) {
                JSONObject(conn.inputStream.bufferedReader().readText())
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("InnerTube", "next failed", e)
            null
        }
    }

    // contents.twoColumnWatchNextResults.secondaryResults.secondaryResults.results[]
    private fun parseWebRelated(json: JSONObject): List<RelatedVideo> {
        val results = json.optJSONObject("contents")
            ?.optJSONObject("twoColumnWatchNextResults")
            ?.optJSONObject("secondaryResults")
            ?.optJSONObject("secondaryResults")
            ?.optJSONArray("results") ?: return emptyList()
        val list = ArrayList<RelatedVideo>()
        for (i in 0 until results.length()) {
            val lockup = results.optJSONObject(i)?.optJSONObject("lockupViewModel") ?: continue
            if (lockup.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") continue
            val id = lockup.optString("contentId")
            if (id.isBlank()) continue
            val meta = lockup.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel")
            val title = meta?.optJSONObject("title")?.optString("content").orEmpty()
            val rows = meta?.optJSONObject("metadata")
                ?.optJSONObject("contentMetadataViewModel")
                ?.optJSONArray("metadataRows")
            val artist = rows?.optJSONObject(0)?.optJSONArray("metadataParts")
                ?.optJSONObject(0)?.optJSONObject("text")?.optString("content").orEmpty()
            val thumb = lockup.optJSONObject("contentImage")
                ?.optJSONObject("thumbnailViewModel")
                ?.optJSONObject("image")
                ?.optJSONArray("sources")
                ?.optJSONObject(0)?.optString("url")
            list += RelatedVideo(
                id, title, artist, thumb?.takeIf { it.isNotBlank() },
                durationMs = lockupDurationMs(lockup)
            )
        }
        return list
    }

    // ...results.watchNextTabbedResultsRenderer.tabs[1]...itemSectionRenderer.contents[]
    private fun parseVrRelated(json: JSONObject): List<RelatedVideo> {
        val tabs = json.optJSONObject("contents")
            ?.optJSONObject("singleColumnWatchNextResults")
            ?.optJSONObject("results")
            ?.optJSONObject("watchNextTabbedResultsRenderer")
            ?.optJSONArray("tabs") ?: return emptyList()
        val list = ArrayList<RelatedVideo>()
        for (t in 0 until tabs.length()) {
            val contents = tabs.optJSONObject(t)
                ?.optJSONObject("tabRenderer")
                ?.optJSONObject("content")
                ?.optJSONObject("sectionListRenderer")
                ?.optJSONArray("contents") ?: continue
            for (s in 0 until contents.length()) {
                val items = contents.optJSONObject(s)
                    ?.optJSONObject("itemSectionRenderer")
                    ?.optJSONArray("contents") ?: continue
                appendRenderers(items, list)
            }
        }
        return list
    }

    private fun appendRenderers(items: JSONArray, out: MutableList<RelatedVideo>) {
        for (i in 0 until items.length()) {
            val r = items.optJSONObject(i)?.optJSONObject("compactVideoRenderer") ?: continue
            val id = r.optString("videoId")
            if (id.isBlank()) continue
            val title = runsText(r.optJSONObject("title"))
            val artist = runsText(r.optJSONObject("longBylineText"))
                .ifBlank { runsText(r.optJSONObject("shortBylineText")) }
            val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
            val thumb = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))
                ?.optString("url")
            out += RelatedVideo(
                id, title, artist, thumb?.takeIf { it.isNotBlank() },
                durationMs = parseDurationMs(runsText(r.optJSONObject("lengthText")))
            )
        }
    }

    private fun runsText(obj: JSONObject?): String {
        val runs = obj?.optJSONArray("runs") ?: return obj?.optString("simpleText").orEmpty()
        val sb = StringBuilder()
        for (i in 0 until runs.length()) sb.append(runs.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString()
    }

    // Lightweight text search (no yt-dlp) used to top up the Mix. Returns videos
    // only — playlists/channels in the result page are skipped.
    suspend fun searchVideos(query: String, limit: Int = 20): List<RelatedVideo> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, RelatedVideo>()
            try {
                val url = URL("https://www.youtube.com/youtubei/v1/search?key=$API_KEY")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.connectTimeout = 5000
                conn.readTimeout = 8000
                conn.doOutput = true
                val body = """{"context":{"client":{$WEB_CONTEXT}},"query":"${query.replace("\"", "'")}"}"""
                conn.outputStream.write(body.toByteArray())
                if (conn.responseCode != 200) return@withContext emptyList()
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val sections = json.optJSONObject("contents")
                    ?.optJSONObject("twoColumnSearchResultsRenderer")
                    ?.optJSONObject("primaryContents")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents") ?: return@withContext emptyList()
                for (s in 0 until sections.length()) {
                    val items = sections.optJSONObject(s)
                        ?.optJSONObject("itemSectionRenderer")
                        ?.optJSONArray("contents") ?: continue
                    for (i in 0 until items.length()) {
                        val item = items.optJSONObject(i) ?: continue
                        item.optJSONObject("videoRenderer")?.let { r ->
                            val id = r.optString("videoId")
                            if (id.isNotBlank() && out.size < limit) {
                                val title = runsText(r.optJSONObject("title"))
                                val artist = runsText(r.optJSONObject("ownerText"))
                                    .ifBlank { runsText(r.optJSONObject("longBylineText")) }
                                val thumbs = r.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                val thumb = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))
                                    ?.optString("url")
                                out.putIfAbsent(
                                    id,
                                    RelatedVideo(
                                        id, title, artist, thumb,
                                        subscribers = subscriberCount(r),
                                        durationMs = parseDurationMs(
                                            runsText(r.optJSONObject("lengthText"))
                                        )
                                    )
                                )
                            }
                        }
                        item.optJSONObject("lockupViewModel")?.let { r ->
                            val id = r.optString("contentId")
                            if (id.isBlank() || r.optString("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return@let
                            val meta = r.optJSONObject("metadata")
                                ?.optJSONObject("lockupMetadataViewModel")
                            val title = meta?.optJSONObject("title")?.optString("content").orEmpty()
                            val artist = meta?.optJSONObject("metadata")
                                ?.optJSONObject("contentMetadataViewModel")
                                ?.optJSONArray("metadataRows")
                                ?.optJSONObject(0)?.optJSONArray("metadataParts")
                                ?.optJSONObject(0)?.optJSONObject("text")
                                ?.optString("content").orEmpty()
                            val thumb = r.optJSONObject("contentImage")
                                ?.optJSONObject("thumbnailViewModel")
                                ?.optJSONObject("image")
                                ?.optJSONArray("sources")
                                ?.optJSONObject(0)?.optString("url")
                            if (out.size < limit) {
                                out.putIfAbsent(
                                    id,
                                    RelatedVideo(
                                        id, title, artist, thumb,
                                        durationMs = lockupDurationMs(r)
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("InnerTube", "search failed", e)
            }
            out.values.filter { !isJunkTrack(it.title, it.durationMs) }
        }

    // Discover Underground: a handful of genre searches, dropping channels that
    // advertise >100k subscribers when the search layout reports a count.
    suspend fun discoverUnderground(limit: Int = 20): List<RelatedVideo> =
        withContext(Dispatchers.IO) {
            val out = LinkedHashMap<String, RelatedVideo>()
            for (genre in UNDERGROUND_GENRES.shuffled()) {
                if (out.size >= limit) break
                runCatching { searchVideos(genre, limit = 12) }
                    .getOrDefault(emptyList())
                    .filter { it.subscribers == null || it.subscribers < 100_000 }
                    .forEach { out.putIfAbsent(it.videoId, it) }
            }
            Log.d("DISCOVER", "underground picks: ${out.size}")
            out.values.shuffled().take(limit)
        }

    private fun subscriberCount(r: JSONObject): Long? {
        val text = runsText(r.optJSONObject("subscriberCountText"))
            .ifBlank {
                runsText(
                    r.optJSONObject("videoOwnerRenderer")
                        ?.optJSONObject("subscriberCountText")
                )
            }
        if (text.isBlank()) return null
        val match = SUBSCRIBER.find(text) ?: return null
        val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return (value * when (match.groupValues[2].lowercase()) {
            "k" -> 1_000
            "m" -> 1_000_000
            "b" -> 1_000_000_000
            else -> 1
        }).toLong()
    }

    private val UNDERGROUND_GENRES = listOf(
        "indie shoegaze",
        "underground rap 2024",
        "bedroom pop",
        "underground electronic",
        "post punk revival",
        "lo-fi jazz"
    )
    private val SUBSCRIBER = Regex("([0-9][0-9.,]*)\\s*([KkMmBb])?")
}
