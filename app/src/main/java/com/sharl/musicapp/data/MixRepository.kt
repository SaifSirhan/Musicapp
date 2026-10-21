package com.sharl.musicapp.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Builds the "Infinite Radio" queue: a seed song, ~40 algorithmic neighbours
// from YouTube's related list and ~30 tracks from the user's other top artists.
class MixRepository(context: Context) {
    private val appContext = context.applicationContext
    private val dao = AppDatabase.get(context).songDao()

    suspend fun generateMix(seed: Song): List<Song> = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val related = fetchRelated(seed)
        val diversity = fetchDiversity(seed)
        val hidden = runCatching { dao.hiddenIds() }.getOrDefault(emptyList()).toHashSet()

        val seen = hashSetOf(seed.videoId)
        // YouTube returns several uploads of the same recording (video/audio/
        // lyric versions), so dedupe on the title as well as the video id.
        val seenTitles = hashSetOf(titleKey(seed))
        val pool = (related + diversity).filter {
            // Junk titles are also dropped here so library-sourced diversity
            // tracks ("… (Lyrics)") can't slip into an otherwise clean mix.
            it.videoId !in hidden && !isJunk(it.title) &&
                seen.add(it.videoId) && seenTitles.add(titleKey(it))
        }

        // Phase 22: one in five mixes gets a short album run, kept contiguous
        // and in album order instead of being shuffled with the rest.
        val albumBlock = buildAlbumBlock(seed, related + diversity, hidden)
            .filter { seen.add(it.videoId) && seenTitles.add(titleKey(it)) }
        // Phase 23: shuffle first, then stable-sort so official audio and the
        // seed artist's own uploads lead the station without making the rest
        // deterministic.
        val shuffled = pool.shuffled().sortedByDescending { qualityScore(it, seed.artist) }
        val insertAt = if (albumBlock.isEmpty() || shuffled.isEmpty()) shuffled.size
            else shuffled.indices.random()
        val mix = listOf(seed) + shuffled.take(insertAt) + albumBlock + shuffled.drop(insertAt)
        Log.d("MIX", "seed=${seed.videoId} related=${related.size} diversity=${diversity.size} " +
            "album=${albumBlock.size} hidden=${hidden.size} total=${mix.size} " +
            "in ${System.currentTimeMillis() - started}ms")
        mix
    }

    // 20% of the time, queue 3-4 tracks from one album together. Song has no
    // track-number column, so album order is approximated by title.
    private suspend fun buildAlbumBlock(
        seed: Song,
        pool: List<Song>,
        hidden: Set<String>
    ): List<Song> {
        if (kotlin.random.Random.nextInt(100) >= 20) return emptyList()
        val album = seed.album?.takeIf { it.isNotBlank() }
            ?: pool.mapNotNull { it.album?.takeIf(String::isNotBlank) }.randomOrNull()
            ?: runCatching { dao.randomAlbum() }.getOrNull()
            ?: return emptyList()
        val tracks = dao.songsByAlbum(album, 4)
            .filter { it.videoId !in hidden && it.videoId != seed.videoId && !isJunk(it.title) }
        if (tracks.size < 3) return emptyList()
        Log.d("MIX", "album block: $album -> ${tracks.map { it.title }}")
        return tracks
    }

    private suspend fun fetchRelated(seed: Song): List<Song> {
        val out = LinkedHashMap<String, Song>()
        runCatching { InnerTubeHelper.getRelatedVideos(seed.videoId) }
            .getOrDefault(emptyList())
            .forEach { r -> addRelated(out, r, seed) }
        Log.d("MIX", "InnerTube related: ${out.size}")

        // YouTube caps the related list around 20 — top up with InnerTube text
        // search. yt-dlp is deliberately not used here: a search can take a
        // minute on a cold emulator and there is no way to keep the spinner
        // bounded while blocking on it.
        val queries = if (out.isEmpty()) {
            listOf("${seed.artist} ${seed.title}", "${seed.artist} songs", "${seed.artist} mix")
        } else {
            listOf("${seed.artist} ${seed.title}", "${seed.artist} songs")
        }
        for (query in queries) {
            if (out.size >= RELATED_TARGET) break
            runCatching { InnerTubeHelper.searchVideos(query) }
                .getOrDefault(emptyList())
                .forEach { r -> addRelated(out, r, seed) }
        }
        return out.values.take(RELATED_TARGET)
    }

    private fun addRelated(out: MutableMap<String, Song>, r: RelatedVideo, seed: Song) {
        if (r.videoId.isBlank() || r.videoId == seed.videoId) return
        // Duration + title sanity: >15min, <30s, lyrics/live/mixes/podcasts...
        if (InnerTubeHelper.isJunkTrack(r.title, r.durationMs) || isJunk(r.title)) return
        if (out.size >= RELATED_TARGET) return
        out.putIfAbsent(
            r.videoId,
            Song(
                videoId = r.videoId,
                title = r.title.ifBlank { r.videoId },
                artist = r.artist.ifBlank { guessArtist(r.title) ?: seed.artist },
                thumbnailUrl = r.thumbnailUrl
                    ?: "https://i.ytimg.com/vi/${r.videoId}/mqdefault.jpg",
                lastPlayedAt = 0L,
                playCount = 0
            )
        )
    }

    // Three random tracks each from the ten most-played artists other than the seed's.
    private suspend fun fetchDiversity(seed: Song): List<Song> {
        val artists = dao.topArtists(10, seed.artist)
        val out = ArrayList<Song>()
        for (artist in artists) {
            if (out.size >= DIVERSITY_TARGET) break
            out += dao.randomSongsByArtist(artist, 3)
        }
        return out.take(DIVERSITY_TARGET)
    }

    // "Artist - Title" is the common upload format; ignore trailing junk.
    private fun guessArtist(title: String): String? {
        val idx = title.indexOf(" - ")
        if (idx <= 0) return null
        return title.substring(0, idx).trim().takeIf { it.isNotEmpty() }
    }

    private fun isJunk(title: String): Boolean = JUNK.containsMatchIn(title)

    // Higher score sorts earlier. Official audio first, then any "audio" upload,
    // then the seed artist's own channel / auto-generated "- Topic" channels.
    private fun qualityScore(song: Song, seedArtist: String): Int {
        val title = song.title.lowercase()
        val artist = song.artist.lowercase()
        var score = 0
        if (OFFICIAL_AUDIO.containsMatchIn(title)) score += 5
        else if (AUDIO.containsMatchIn(title)) score += 3
        if (artist.endsWith(" - topic")) score += 3
        if (seedArtist.isNotBlank() && artist.contains(seedArtist.lowercase())) score += 2
        return score
    }

    // Drops "(Official Video)", "[Lyrics]" and the artist name so different
    // uploads of the same recording collapse onto one key.
    private fun titleKey(song: Song): String {
        val stripped = song.title.replace(BRACKETS, " ")
        val noArtist = if (song.artist.isBlank()) stripped else
            stripped.replace(Regex(Regex.escape(song.artist), RegexOption.IGNORE_CASE), " ")
        return noArtist.lowercase().replace(NON_ALNUM, " ").trim()
            .ifEmpty { song.title.lowercase() }
    }

    private companion object {
        const val RELATED_TARGET = 40
        const val DIVERSITY_TARGET = 30
        val BRACKETS = Regex("\\([^)]*\\)|\\[[^\\]]*\\]|\\{[^}]*\\}")
        val NON_ALNUM = Regex("[^a-z0-9]+")
        // Phase 23 junk list. Word boundaries keep "Remix" and "Deliver" safe.
        val JUNK = Regex(
            "\\b(lyrics?|live|mix|hours?|compilations?|reactions?|tutorials?|podcasts?|" +
                "playlist|full album|livestream|live stream|full performance|audition|" +
                "best of|best songs|greatest hits|top songs|interview|behind the scenes|" +
                "trailer|vlog|prank|x factor|got talent)\\b",
            RegexOption.IGNORE_CASE
        )
        val OFFICIAL_AUDIO = Regex("\\bofficial audio\\b", RegexOption.IGNORE_CASE)
        val AUDIO = Regex("\\baudio\\b", RegexOption.IGNORE_CASE)
    }
}
