package com.sharl.musicapp.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDL.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ImportResolver(private val context: Context) {

    private companion object {
        const val MAX_TRACKS = 200
        const val SEARCH_SPACING_MS = 1000L
    }

    @Volatile
    private var initialized = false

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            YoutubeDL.getInstance().init(context.applicationContext)
            try {
                YoutubeDL.getInstance().updateYoutubeDL(context, UpdateChannel.NIGHTLY)
            } catch (e: Exception) {
                Log.e("IMPORT", "yt-dlp update failed", e)
            }
            initialized = true
        }
    }

    // onProgress reports resolution progress (done, total) for the sources that need
    // per-track yt-dlp searches. YTM needs none, so it never calls it.
    suspend fun resolve(
        source: ImportSource,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ImportPreview = withContext(Dispatchers.IO) {
        when (source) {
            is ImportSource.YtmUrl -> resolveYtm(source.url)
            is ImportSource.SpotifyUrl -> resolveSpotify(source.url, onProgress)
            is ImportSource.TextPaste -> resolveText(source.blob, onProgress)
            is ImportSource.CsvFile -> resolveFile(source.uri, onProgress)
        }
    }

    // ---- B1: YouTube Music / YouTube playlist URL -----------------------------

    private suspend fun resolveYtm(url: String): ImportPreview {
        ensureInitialized()
        val json = runDumpJson(url)
            ?: throw ImportException("Could not read that playlist. Check the link and try again.")
        val entries = parseEntries(json)
        if (entries.isEmpty()) {
            throw ImportException("That playlist has no readable tracks.")
        }
        val name = json.optString("title", "").takeIf { it.isNotBlank() } ?: "Imported playlist"
        return ImportPreview(sourceName = name, tracks = entries.take(MAX_TRACKS))
    }

    // ---- B2: Spotify playlist URL (embed metadata + yt-dlp search) -------------

    private suspend fun resolveSpotify(url: String, onProgress: (Int, Int) -> Unit): ImportPreview {
        val id = Regex("/playlist/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1)
            ?: throw ImportException("That doesn't look like a Spotify playlist link.")
        val html = httpGet("https://open.spotify.com/embed/playlist/$id")
            ?: throw ImportException("Playlist is private or not found. Spotify import requires a public playlist.")
        val nextData = extractNextData(html)
            ?: throw ImportException("Playlist is private or not found. Spotify import requires a public playlist.")

        val entity = try {
            nextData.getJSONObject("props").getJSONObject("pageProps")
                .getJSONObject("state").getJSONObject("data").getJSONObject("entity")
        } catch (e: Exception) {
            throw ImportException("Playlist is private or not found. Spotify import requires a public playlist.")
        }

        val trackList: JSONArray = entity.optJSONArray("trackList")
            ?: throw ImportException("Playlist is private or not found. Spotify import requires a public playlist.")
        if (trackList.length() == 0) {
            throw ImportException("Playlist is private or not found. Spotify import requires a public playlist.")
        }

        val name = entity.optString("name", "").takeIf { it.isNotBlank() } ?: "Imported playlist"
        val tracks = ArrayList<ImportTrack>()
        for (i in 0 until trackList.length()) {
            val t = trackList.optJSONObject(i) ?: continue
            val title = t.optString("title", "").trim()
            if (title.isEmpty()) continue
            tracks.add(ImportTrack(title = title, artist = t.optString("subtitle", "").trim()))
            if (tracks.size >= MAX_TRACKS) break
        }
        return resolveUnresolved(name, tracks, onProgress)
    }

    private fun extractNextData(html: String): JSONObject? {
        val marker = "id=\"__NEXT_DATA__\""
        val markerIdx = html.indexOf(marker)
        if (markerIdx < 0) return null
        val open = html.indexOf('>', markerIdx)
        val close = html.indexOf("</script>", open)
        if (open < 0 || close < 0) return null
        val raw = html.substring(open + 1, close).trim()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            null
        }
    }

    // ---- B3: pasted text -------------------------------------------------------

    private suspend fun resolveText(blob: String, onProgress: (Int, Int) -> Unit): ImportPreview {
        val tracks = ArrayList<ImportTrack>()
        for (line in blob.split("\n").map { it.trim() }.filter { it.isNotEmpty() }) {
            val (artist, title) = splitArtistTitle(line)
            tracks.add(ImportTrack(title = title, artist = artist))
            if (tracks.size >= MAX_TRACKS) break
        }
        val name = "Imported " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return resolveUnresolved(name, tracks, onProgress)
    }

    // Splits on " - ", " – " (en dash) or " — " (em dash). "Artist - Title" → (artist, title).
    private fun splitArtistTitle(line: String): Pair<String, String> {
        for (sep in listOf(" - ", " – ", " — ")) {
            val idx = line.indexOf(sep)
            if (idx > 0) {
                return line.substring(0, idx).trim() to line.substring(idx + sep.length).trim()
            }
        }
        return "" to line
    }

    // ---- B4: CSV / JSON file ---------------------------------------------------

    private suspend fun resolveFile(uri: Uri, onProgress: (Int, Int) -> Unit): ImportPreview {
        val text = try {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        } catch (e: Exception) {
            Log.e("IMPORT", "read failed: ${e.message}")
            null
        } ?: throw ImportException("Could not read that file.")

        val trimmed = text.trimStart()
        val tracks = if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
            parseJsonTracks(trimmed)
        } else {
            parseCsvTracks(trimmed)
        }
        val name = fileDisplayName(uri)
            ?: ("Imported " + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()))
        return resolveUnresolved(name, tracks, onProgress)
    }

    private fun parseCsvTracks(text: String): List<ImportTrack> {
        val lines = text.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return emptyList()
        var titleIdx = 0
        var artistIdx = 1
        var start = 0
        val first = splitCsvLine(lines[0]).map { it.lowercase() }
        if (first.contains("title") || first.contains("artist")) {
            titleIdx = first.indexOf("title").takeIf { it >= 0 } ?: 0
            artistIdx = first.indexOf("artist").takeIf { it >= 0 } ?: 1
            start = 1
        }
        val out = ArrayList<ImportTrack>()
        for (i in start until lines.size) {
            val fields = splitCsvLine(lines[i])
            val title = fields.getOrNull(titleIdx)?.trim().orEmpty()
            if (title.isEmpty()) continue
            out.add(ImportTrack(title = title, artist = fields.getOrNull(artistIdx)?.trim().orEmpty()))
            if (out.size >= MAX_TRACKS) break
        }
        return out
    }

    // Quote-aware split (handles "a, b",c). Kept minimal — import schema is title,artist.
    private fun splitCsvLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && inQuotes && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> {
                    out.add(sb.toString()); sb.setLength(0)
                }
                else -> sb.append(c)
            }
            i++
        }
        out.add(sb.toString())
        return out
    }

    private fun parseJsonTracks(text: String): List<ImportTrack> {
        val out = ArrayList<ImportTrack>()
        try {
            val root: Any = if (text.startsWith("[")) JSONArray(text) else JSONObject(text)
            val arr: JSONArray = when (root) {
                is JSONArray -> root
                is JSONObject -> root.optJSONArray("items")
                    ?: root.optJSONArray("tracks")
                    ?: JSONArray()
                else -> JSONArray()
            }
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // Spotify export shape: {"track":{"name":..,"artists":[{"name":..}]}}
                val trackObj = o.optJSONObject("track") ?: o
                val title = trackObj.optString("title", "")
                    .takeIf { it.isNotBlank() } ?: trackObj.optString("name", "").trim()
                if (title.isEmpty()) continue
                val artist = artistFromJson(trackObj)
                out.add(ImportTrack(title = title, artist = artist))
                if (out.size >= MAX_TRACKS) break
            }
        } catch (e: Exception) {
            throw ImportException("Could not parse that JSON file.")
        }
        return out
    }

    private fun artistFromJson(o: JSONObject): String {
        o.optString("artist", "").trim().takeIf { it.isNotEmpty() }?.let { return it }
        val artists = o.optJSONArray("artists") ?: return ""
        val names = ArrayList<String>()
        for (i in 0 until artists.length()) {
            val name = artists.optJSONObject(i)?.optString("name", "")?.trim().orEmpty()
            if (name.isNotEmpty()) names.add(name)
        }
        return names.joinToString(", ")
    }

    private fun fileDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    c.getString(idx)?.substringBeforeLast('.')
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---- shared: per-track yt-dlp search --------------------------------------

    private suspend fun resolveUnresolved(
        name: String,
        tracks: List<ImportTrack>,
        onProgress: (Int, Int) -> Unit
    ): ImportPreview {
        ensureInitialized()
        val resolved = ArrayList<ImportTrack>()
        val unresolved = ArrayList<ImportTrack>()
        val total = tracks.size
        onProgress(0, total)
        for ((i, track) in tracks.withIndex()) {
            val query = listOf(track.artist, track.title)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            val match = searchOne(query)
            if (match != null) {
                resolved.add(track.copy(videoId = match.first, thumbnailUrl = match.second))
            } else {
                unresolved.add(track)
            }
            onProgress(i + 1, total)
            if (i < total - 1) delay(SEARCH_SPACING_MS)
        }
        return ImportPreview(sourceName = name, tracks = resolved, unresolved = unresolved)
    }

    // Returns (videoId, thumbnailUrl) of the first search hit, or null.
    private fun searchOne(query: String): Pair<String, String?>? {
        if (query.isBlank()) return null
        val json = runDumpJson("ytsearch1:$query") ?: return null
        val entry = json.optJSONArray("entries")?.optJSONObject(0) ?: return null
        val id = entry.optString("id", "")
        if (id.isEmpty()) return null
        return id to lastThumb(entry)
    }

    private fun runDumpJson(target: String): JSONObject? {
        return try {
            val request = YoutubeDLRequest(target).apply {
                addOption("--flat-playlist")
                addOption("--dump-single-json")
                addOption("--no-update")
                addOption("--no-warnings")
            }
            val out = YoutubeDL.getInstance().execute(request).out
            val start = out.indexOf('{')
            if (start < 0) return null
            JSONObject(out.substring(start).trim())
        } catch (e: Exception) {
            Log.e("IMPORT", "yt-dlp failed for $target: ${e.message}")
            null
        }
    }

    private fun parseEntries(json: JSONObject): List<ImportTrack> {
        val entries = json.optJSONArray("entries") ?: return emptyList()
        val list = ArrayList<ImportTrack>()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            val id = e.optString("id", "")
            if (id.isEmpty()) continue
            list.add(
                ImportTrack(
                    title = e.optString("title", ""),
                    artist = clean(e.optString("uploader", ""))
                        ?: clean(e.optString("channel", "")) ?: "",
                    videoId = id,
                    thumbnailUrl = lastThumb(e)
                )
            )
            if (list.size >= MAX_TRACKS) break
        }
        return list
    }

    private fun lastThumb(e: JSONObject): String? {
        val arr = e.optJSONArray("thumbnails") ?: return null
        if (arr.length() == 0) return null
        return clean(arr.optJSONObject(arr.length() - 1)?.optString("url", "") ?: "")
    }

    private fun clean(v: String): String? =
        v.trim().takeIf { it.isNotEmpty() && it != "null" && it != "NA" }

    private fun httpGet(url: String): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Android) MusicApp/1.0"
                )
                setRequestProperty("Accept", "text/html")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e("IMPORT", "httpGet failed for $url: ${e.message}")
            null
        }
    }
}
