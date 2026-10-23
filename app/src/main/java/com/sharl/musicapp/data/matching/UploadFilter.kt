package com.sharl.musicapp.data.matching

import com.sharl.musicapp.data.matching.TitleNormalizer.simple
import java.util.Locale

/** How much a channel can be trusted to be the rights holder of what it uploads. */
enum class ChannelTrust { TOPIC, VEVO, ARTIST, UNTRUSTED }

/**
 * Admission rules for search results, shared by album discovery and track search.
 *
 * Channel trust decides whether an upload is trusted at all; the blocklist then only
 * has to police the channels that are not. Title words alone can not be the signal,
 * because "Under Cover of Darkness" and "While My Guitar Gently Weeps" are real songs
 * on their own artists' channels.
 */
object UploadFilter {

    /** Task 3: an "album" longer than this is a compilation or a podcast. */
    const val MAX_ALBUM_UPLOAD_MS = 3_600_000L

    private val TOPIC_SUFFIX = Regex("\\s*-\\s*topic\\s*$", RegexOption.IGNORE_CASE)
    private val VEVO_SUFFIX = Regex("\\s*-?\\s*vevo\\s*$", RegexOption.IGNORE_CASE)

    // Rejected on any channel, but only when the upload claims to be an album:
    // "Live at the Hollywood Bowl" is a package, while "Song (Live at Home)" is a track.
    private val COMPILATION = (
        listOf("best of", "greatest hits", "full album", "top 10", "top 20", "all songs", "live at")
            .map { it to phraseRegex(it) } +
        listOf("compilation", "mix", "playlist", "lyric", "concert", "livestream", "podcast")
            .map { it to wordRegex(it) }
        ).toMap()

    // Rejected only on channels that are not trusted. "reaction" is a video format,
    // not a package shape, so an artist titled "Reaction" keeps its own uploads.
    private val NON_MUSIC = (
        listOf("8d audio", "slowed reverb").map { it to phraseRegex(it) } +
        listOf(
            "guitar", "tab", "tutorial", "cover", "repost", "instrumental", "karaoke",
            "reaction", "tiktok", "shorts", "mashup", "remix", "slowed", "reverb", "lyric"
        ).map { it to wordRegex(it) }
        ).toMap()

    fun trust(artist: String, channel: String?): ChannelTrust {
        val raw = channel?.trim().orEmpty()
        if (raw.isBlank()) return ChannelTrust.UNTRUSTED
        if (TOPIC_SUFFIX.containsMatchIn(raw)) return ChannelTrust.TOPIC
        if (VEVO_SUFFIX.containsMatchIn(raw)) return ChannelTrust.VEVO
        val target = simple(artist)
        val uploaded = simple(raw)
        if (target.isBlank() || uploaded.isBlank()) return ChannelTrust.UNTRUSTED
        // Equality, not containment: "Best Of The Strokes HQ" is not the artist's channel.
        // Spacing differences inside the name are kept, so the official MADtv channel
        // does not equal the query "mad tv" and is handled as any other channel.
        return if (uploaded == target) ChannelTrust.ARTIST else ChannelTrust.UNTRUSTED
    }

    fun priority(trust: ChannelTrust): Int = when (trust) {
        ChannelTrust.TOPIC -> 0
        ChannelTrust.VEVO -> 1
        ChannelTrust.ARTIST -> 2
        ChannelTrust.UNTRUSTED -> 3
    }

    fun rejectionReason(
        artist: String,
        channel: String?,
        title: String,
        durationMs: Long?,
        albumContext: Boolean,
        strictChannel: Boolean = true
    ): String? {
        val trust = trust(artist, channel)
        val vocabulary = if (trust == ChannelTrust.UNTRUSTED) {
            if (strictChannel) return "channel:${channel?.takeIf { it.isNotBlank() } ?: "unknown"}"
            if (albumContext) COMPILATION + NON_MUSIC else NON_MUSIC
        } else {
            if (albumContext) COMPILATION else emptyMap()
        }
        val lower = title.lowercase(Locale.ROOT)
        vocabulary.entries.firstOrNull { (_, regex) -> regex.containsMatchIn(lower) }
            ?.let { return "title:${it.key}" }
        if (albumContext && durationMs != null && durationMs > MAX_ALBUM_UPLOAD_MS) {
            return "duration:$durationMs"
        }
        return null
    }

    fun accepts(
        artist: String,
        channel: String?,
        title: String,
        durationMs: Long?,
        albumContext: Boolean,
        strictChannel: Boolean = true
    ): Boolean = rejectionReason(artist, channel, title, durationMs, albumContext, strictChannel) == null

    private fun phraseRegex(phrase: String): Regex =
        Regex("\\b" + phrase.split(' ').joinToString("\\s+") + "s?\\b", RegexOption.IGNORE_CASE)

    private fun wordRegex(word: String): Regex =
        Regex("\\b$word(?:s|es)?\\b", RegexOption.IGNORE_CASE)
}
