package com.sharl.musicapp.data.matching

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative title normalization for duplicate detection and candidate matching.
 *
 * Only presentation decoration is removed. Version markers ("Pt. 2", "Remix", "Live",
 * "Acoustic"), artist prefixes, and meaningful bracket content are preserved so that
 * distinct recordings never collapse into one identity.
 */
object TitleNormalizer {

    const val NORMALIZATION_VERSION = 1

    data class NormalizedTitle(
        val core: String,
        val variants: Set<String>
    ) {
        val isBlank: Boolean get() = core.isBlank()
    }

    private val BRACKET = Regex("[\\(\\[\\{]([^\\)\\]\\}]*)[\\)\\]\\}]")
    private val TITLE_SEPARATOR = Regex("\\s+[-–—|]\\s+")
    private val WHITESPACE = Regex("\\s+")
    private val MARKS = Regex("\\p{M}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    private val APOSTROPHE = Regex("['’‘`]")
    private val TRACK_PREFIX = Regex(
        "^(?:track\\s*)?0?\\d{1,2}\\s*(?:[.)]|[-–—])\\s+",
        RegexOption.IGNORE_CASE
    )
    private val SUFFIX_VARIANT = Regex(
        "\\b(?:remix(?:ed)?|live|acoustic|instrumental|karaoke|nightcore|sped[- ]?up|slowed|extended|demo|remaster(?:ed)?)\\s*$"
    )

    private val DECORATION_TOKENS = setOf(
        "official", "audio", "video", "lyric", "lyrics", "visualizer", "visualiser",
        "mv", "m", "v", "hd", "hq", "4k", "8k", "1080p", "720p", "explicit",
        "music", "with", "stream", "streaming", "full", "album"
    )

    private val PART_WORDS = setOf("part", "vol", "volume", "chapter", "disc", "disk", "book", "act")

    private val ROMAN = mapOf(
        "i" to "1", "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10"
    )

    private val VARIANT_KEYWORDS: Map<String, Regex> = linkedMapOf(
        "remix" to Regex("\\bremix(?:ed)?\\b|\\bbootleg\\b"),
        "live" to Regex("\\blive\\b"),
        "acoustic" to Regex("\\bacoustic\\b|\\bunplugged\\b"),
        "instrumental" to Regex("\\binstrumental\\b"),
        "karaoke" to Regex("\\bkaraoke\\b"),
        "cover" to Regex("\\bcover\\b"),
        "demo" to Regex("\\bdemo\\b"),
        "sped_up" to Regex("\\bsped[- ]?up\\b|\\bspeed[- ]?up\\b"),
        "slowed" to Regex("\\bslowed\\b|\\breverb\\b"),
        "nightcore" to Regex("\\bnightcore\\b"),
        "remaster" to Regex("\\bremaster(?:ed)?\\b"),
        "extended" to Regex("\\bextended\\b"),
        "radio_edit" to Regex("\\bradio\\s+edit\\b"),
        "session" to Regex("\\bsession\\b"),
        "version" to Regex("\\b(?:album|single|original|deluxe)\\s+version\\b")
    )

    fun normalize(rawTitle: String, artist: String? = null): NormalizedTitle {
        val nfkc = Normalizer.normalize(rawTitle, Normalizer.Form.NFKC).trim()
        val variants = detectVariants(nfkc)
        var text = stripArtistPrefix(nfkc, artist)
        text = removeBracketDecorations(text)
        text = removeDecorativeAffixes(text)
        text = stripTrackNumberPrefix(text)
        return NormalizedTitle(core = simple(text), variants = variants)
    }

    /** The `songs.normalizedTitle` value for a stored row. */
    fun normalizedCore(title: String, artist: String? = null): String =
        normalize(title, artist).core

    /**
     * Folds case, diacritics, ampersands, punctuation, and whitespace. Unicode letters
     * are preserved. Safe to apply to a single segment or to a whole title.
     */
    fun simple(raw: String): String {
        // NFKC first (compatibility forms), then NFD so precomposed diacritics separate
        // into base letter + mark and can be folded without dropping the base letter.
        val canonical = Normalizer.normalize(
            Normalizer.normalize(raw, Normalizer.Form.NFKC),
            Normalizer.Form.NFD
        )
        val words = canonical.replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(APOSTROPHE, "")
            .replace("&", " and ")
            .replace(NON_WORD, " ")
            .trim()
            .split(' ')
            .filter { it.isNotEmpty() }
        return canonicalize(words).joinToString(" ")
    }

    private fun canonicalize(words: List<String>): List<String> {
        val out = ArrayList<String>(words.size)
        for (word in words) {
            val normalized = if (word == "pt") "part" else word
            val mapped = if (out.lastOrNull() in PART_WORDS) ROMAN[normalized] ?: normalized else normalized
            out.add(mapped)
        }
        return out
    }

    private fun detectVariants(title: String): Set<String> {
        val lower = title.lowercase(Locale.ROOT)
        val haystacks = ArrayList<String>()
        BRACKET.findAll(lower).forEach { haystacks.add(it.groupValues[1]) }
        val parts = lower.split(TITLE_SEPARATOR)
        if (parts.size > 1) haystacks.add(parts.last())
        SUFFIX_VARIANT.find(lower)?.let { haystacks.add(it.value) }
        if (haystacks.isEmpty()) return emptySet()

        val found = LinkedHashSet<String>()
        for (segment in haystacks) {
            for ((name, regex) in VARIANT_KEYWORDS) {
                if (regex.containsMatchIn(segment)) found.add(name)
            }
        }
        return found
    }

    private fun stripArtistPrefix(input: String, artist: String?): String {
        if (artist.isNullOrBlank()) return input
        val target = simple(artist)
        if (target.isEmpty()) return input
        for (separator in listOf(" - ", " – ", " — ", " | ", ": ")) {
            val idx = input.indexOf(separator)
            if (idx > 0 && simple(input.substring(0, idx)) == target) {
                return input.substring(idx + separator.length)
            }
        }
        return input
    }

    private fun removeBracketDecorations(input: String): String =
        BRACKET.replace(input) { match ->
            if (isDecoration(match.groupValues[1])) " " else match.value
        }.replace(WHITESPACE, " ").trim()

    private fun removeDecorativeAffixes(input: String): String {
        var text = input
        val colon = text.indexOf(':')
        if (colon > 0 && isDecoration(text.substring(0, colon))) {
            text = text.substring(colon + 1)
        }

        val parts = text.split(TITLE_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (parts.size < 2) return text.trim()

        val kept = ArrayDeque(parts)
        while (kept.size > 1 && isDecoration(kept.last())) kept.removeLast()
        while (kept.size > 1 && isDecoration(kept.first())) kept.removeFirst()
        return kept.joinToString(" - ")
    }

    private fun isDecoration(segment: String): Boolean {
        val tokens = simple(segment).split(' ').filter { it.isNotEmpty() }
        return tokens.isNotEmpty() && tokens.all { it in DECORATION_TOKENS }
    }

    private fun stripTrackNumberPrefix(input: String): String {
        val stripped = input.replaceFirst(TRACK_PREFIX, "")
        return if (stripped.any { it.isLetter() }) stripped else input
    }
}
