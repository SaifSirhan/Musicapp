package com.sharl.musicapp.data.matching

import com.sharl.musicapp.data.matching.TitleNormalizer.simple
import kotlin.math.abs

enum class MatchConfidence { HIGH, MEDIUM, LOW, REJECTED }

enum class MatchReason {
    TITLE_EXACT,
    TITLE_STRONG,
    TITLE_PARTIAL,
    TITLE_WEAK,
    DURATION_MATCH,
    DURATION_CLOSE,
    DURATION_CONFLICT,
    DURATION_UNKNOWN,
    ARTIST_MATCH,
    ARTIST_MISMATCH,
    ALBUM_MATCH,
    TRACK_NUMBER_MATCH,
    TOPIC_CHANNEL,
    SONG_RESULT,
    VARIANT_MATCH,
    VARIANT_MISMATCH
}

data class TrackCandidate(
    val videoId: String,
    val rawTitle: String,
    val channel: String = "",
    val durationMs: Long? = null,
    val isSongResult: Boolean = false,
    val candidateArtist: String? = null,
    val albumTitle: String? = null,
    val trackNumber: Int? = null,
    val sourcePlaylistId: String? = null,
    val sourcePosition: Int? = null
)

data class TrackTarget(
    val title: String,
    val artist: String? = null,
    val durationMs: Long? = null,
    val albumTitle: String? = null,
    val trackNumber: Int? = null
)

data class RankedCandidate(
    val candidateIndex: Int,
    val candidate: TrackCandidate,
    val score: Double,
    val titleScore: Double,
    val confidence: MatchConfidence,
    val reasons: List<MatchReason>
)

data class TrackAssignment(
    val targetIndex: Int,
    val candidateIndex: Int,
    val candidate: TrackCandidate,
    val score: Double,
    val confidence: MatchConfidence,
    val reasons: List<MatchReason>
)

data class AssignmentResult(
    val matches: List<TrackAssignment>,
    val needsReviewTargets: List<Int>,
    val unmatchedTargets: List<Int>,
    val unusedCandidateIndices: List<Int>
)

/**
 * Scores search candidates against expected tracks and resolves them with a
 * deterministic one-to-one assignment. Low-confidence and ambiguous candidates are
 * never selected automatically.
 */
object CandidateRanker {

    private const val DURATION_HARD_LIMIT_MS = 15_000L
    private const val DURATION_MATCH_MS = 3_000L
    private const val DURATION_CLOSE_MS = 8_000L

    private const val HIGH_TITLE_SCORE = 0.90
    private const val MEDIUM_SCORE = 0.65
    private const val REJECT_TITLE_SCORE = 0.35
    private const val AMBIGUITY_MARGIN = 0.08

    private val TOPIC_SUFFIX = Regex("\\s*-\\s*topic\\s*$", RegexOption.IGNORE_CASE)
    private val VEVO_SUFFIX = Regex("\\s*-?\\s*vevo\\s*$", RegexOption.IGNORE_CASE)
    private val ARTIST_SPLIT = Regex(
        "\\s*(?:[,;/]|&|\\bfeat\\.?\\b|\\bft\\.?\\b|\\bwith\\b|\\bx\\b)\\s*",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_ARTISTS = setOf(
        "various artists", "various", "va", "unknown", "unknown artist", "topic"
    )

    fun rankAll(target: TrackTarget, candidates: List<TrackCandidate>): List<RankedCandidate> {
        if (candidates.isEmpty()) return emptyList()
        val targetTitle = TitleNormalizer.normalize(target.title, target.artist)
        val targetAlbum = target.albumTitle?.let(::simple)
        return candidates.mapIndexedNotNull { index, candidate ->
            score(target, targetTitle, targetAlbum, index, candidate)
        }
            .filter { it.confidence != MatchConfidence.REJECTED }
            .sortedWith(
                compareByDescending<RankedCandidate> { it.score }
                    .thenByDescending { it.titleScore }
                    .thenBy { it.candidate.videoId }
            )
    }

    fun rank(target: TrackTarget, candidates: List<TrackCandidate>): RankedCandidate? =
        rankAll(target, candidates).firstOrNull()

    fun assign(targets: List<TrackTarget>, candidates: List<TrackCandidate>): AssignmentResult {
        if (targets.isEmpty()) {
            return AssignmentResult(emptyList(), emptyList(), emptyList(), candidates.indices.toList())
        }

        val unique = LinkedHashMap<String, Pair<Int, TrackCandidate>>()
        candidates.forEachIndexed { index, candidate ->
            unique.putIfAbsent(candidate.videoId, index to candidate)
        }
        val pool = unique.values.toList()
        val poolCandidates = pool.map { it.second }

        val needsReview = sortedSetOf<Int>()
        val proposals = ArrayList<Proposal>()

        targets.forEachIndexed { targetIndex, target ->
            val ranked = rankAll(target, poolCandidates)
            if (ranked.isEmpty()) return@forEachIndexed

            val top = ranked.first()
            if (top.confidence == MatchConfidence.LOW) {
                needsReview.add(targetIndex)
                return@forEachIndexed
            }
            val runnerUp = ranked.getOrNull(1)
            if (runnerUp != null &&
                runnerUp.confidence != MatchConfidence.LOW &&
                top.score - runnerUp.score < AMBIGUITY_MARGIN
            ) {
                needsReview.add(targetIndex)
                return@forEachIndexed
            }

            ranked.forEach { candidate ->
                if (candidate.confidence != MatchConfidence.LOW) {
                    proposals.add(Proposal(targetIndex, candidate))
                }
            }
        }

        proposals.sortWith(
            compareByDescending<Proposal> { it.ranked.score }
                .thenByDescending { it.ranked.titleScore }
                .thenBy { it.targetIndex }
                .thenBy { it.ranked.candidate.videoId }
        )

        val usedTargets = BooleanArray(targets.size)
        val usedCandidates = BooleanArray(pool.size)
        val matches = ArrayList<TrackAssignment>()

        for (proposal in proposals) {
            if (usedTargets[proposal.targetIndex]) continue
            val poolIndex = proposal.ranked.candidateIndex
            if (usedCandidates[poolIndex]) continue

            usedTargets[proposal.targetIndex] = true
            usedCandidates[poolIndex] = true
            matches.add(
                TrackAssignment(
                    targetIndex = proposal.targetIndex,
                    candidateIndex = pool[poolIndex].first,
                    candidate = proposal.ranked.candidate,
                    score = proposal.ranked.score,
                    confidence = proposal.ranked.confidence,
                    reasons = proposal.ranked.reasons
                )
            )
        }

        val unmatched = targets.indices.filter { !usedTargets[it] }
        val unused = pool.indices.filter { !usedCandidates[it] }.map { pool[it].first }

        return AssignmentResult(
            matches = matches.sortedBy { it.targetIndex },
            needsReviewTargets = needsReview.toList(),
            unmatchedTargets = unmatched,
            unusedCandidateIndices = unused
        )
    }

    private fun score(
        target: TrackTarget,
        targetTitle: TitleNormalizer.NormalizedTitle,
        targetAlbum: String?,
        index: Int,
        candidate: TrackCandidate
    ): RankedCandidate? {
        val candidateTitle = TitleNormalizer.normalize(candidate.rawTitle, null)
        if (targetTitle.isBlank || candidateTitle.isBlank) return null

        val (titleScore, titleReason) = titleScore(targetTitle.core, candidateTitle.core)
        val reasons = mutableListOf(titleReason)
        var score = titleScore

        var variantCompatible = true
        if (targetTitle.variants != candidateTitle.variants) {
            score -= 0.20
            variantCompatible = false
            reasons.add(MatchReason.VARIANT_MISMATCH)
        } else if (targetTitle.variants.isNotEmpty()) {
            score += 0.05
            reasons.add(MatchReason.VARIANT_MATCH)
        }

        var durationEvidence = false
        val targetDuration = target.durationMs
        val candidateDuration = candidate.durationMs
        if (targetDuration != null && candidateDuration != null &&
            targetDuration > 0 && candidateDuration > 0
        ) {
            val diff = abs(targetDuration - candidateDuration)
            durationEvidence = true
            when {
                diff > DURATION_HARD_LIMIT_MS -> {
                    reasons.add(MatchReason.DURATION_CONFLICT)
                    return RankedCandidate(
                        index, candidate, score, titleScore, MatchConfidence.REJECTED, reasons
                    )
                }
                diff <= DURATION_MATCH_MS -> {
                    score += 0.20
                    reasons.add(MatchReason.DURATION_MATCH)
                }
                diff <= DURATION_CLOSE_MS -> {
                    score += 0.10
                    reasons.add(MatchReason.DURATION_CLOSE)
                }
                else -> {
                    score -= 0.05
                    reasons.add(MatchReason.DURATION_CONFLICT)
                }
            }
        } else {
            reasons.add(MatchReason.DURATION_UNKNOWN)
        }

        val artist = compareArtists(target.artist, candidate.candidateArtist ?: candidate.channel)
        when {
            artist.matched -> {
                score += 0.15
                reasons.add(MatchReason.ARTIST_MATCH)
            }
            artist.conflict -> {
                score -= 0.15
                reasons.add(MatchReason.ARTIST_MISMATCH)
            }
        }

        if (!targetAlbum.isNullOrBlank()) {
            val candidateAlbum = candidate.albumTitle?.let(::simple)
            if (candidateAlbum == targetAlbum) {
                score += 0.05
                reasons.add(MatchReason.ALBUM_MATCH)
            }
        }

        if (target.trackNumber != null && candidate.trackNumber == target.trackNumber) {
            score += 0.05
            reasons.add(MatchReason.TRACK_NUMBER_MATCH)
        }

        val topicChannel = isTopicChannel(candidate.channel)
        if (topicChannel) {
            score += 0.10
            reasons.add(MatchReason.TOPIC_CHANNEL)
        }
        if (candidate.isSongResult) {
            score += 0.05
            reasons.add(MatchReason.SONG_RESULT)
        }

        val corroborated = durationEvidence || artist.matched || topicChannel || candidate.isSongResult
        val confidence = when {
            titleScore < REJECT_TITLE_SCORE -> MatchConfidence.REJECTED
            artist.conflict -> MatchConfidence.LOW
            !variantCompatible -> MatchConfidence.LOW
            score < MEDIUM_SCORE -> MatchConfidence.LOW
            titleScore >= HIGH_TITLE_SCORE && corroborated -> MatchConfidence.HIGH
            else -> MatchConfidence.MEDIUM
        }

        return RankedCandidate(index, candidate, score, titleScore, confidence, reasons)
    }

    private fun titleScore(target: String, candidate: String): Pair<Double, MatchReason> {
        if (target == candidate) return 1.0 to MatchReason.TITLE_EXACT

        val targetTokens = target.split(' ')
        val candidateTokens = candidate.split(' ')
        val targetSet = targetTokens.toSet()
        val candidateSet = candidateTokens.toSet()
        val union = targetSet + candidateSet
        val jaccard = if (union.isEmpty()) 0.0 else (targetSet intersect candidateSet).size.toDouble() / union.size

        val shorterHasSubstance = minOf(targetTokens.size, candidateTokens.size) >= 2
        val containment = if (shorterHasSubstance &&
            (target.contains(candidate) || candidate.contains(target))
        ) 0.90 else 0.0

        val best = maxOf(jaccard, containment)
        val reason = when {
            best >= 0.85 -> MatchReason.TITLE_STRONG
            best >= 0.60 -> MatchReason.TITLE_PARTIAL
            else -> MatchReason.TITLE_WEAK
        }
        return best to reason
    }

    private data class ArtistEvidence(val matched: Boolean, val conflict: Boolean)

    private fun compareArtists(target: String?, candidate: String?): ArtistEvidence {
        val targetSegments = artistSegments(target)
        val candidateSegments = artistSegments(candidate)
        if (targetSegments.isEmpty() || candidateSegments.isEmpty()) {
            return ArtistEvidence(false, false)
        }
        if (targetSegments.any { it in GENERIC_ARTISTS }) {
            return ArtistEvidence(false, false)
        }
        val matched = targetSegments.any { t ->
            candidateSegments.any { c -> c == t || c.contains(t) || t.contains(c) }
        }
        return ArtistEvidence(matched, !matched)
    }

    private fun artistSegments(value: String?): Set<String> {
        if (value.isNullOrBlank()) return emptySet()
        val cleaned = TOPIC_SUFFIX.replace(VEVO_SUFFIX.replace(value, ""), "").trim()
        if (cleaned.isEmpty()) return emptySet()
        return cleaned.split(ARTIST_SPLIT)
            .map { simple(it) }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun isTopicChannel(channel: String): Boolean =
        TOPIC_SUFFIX.containsMatchIn(channel.trim())

    private data class Proposal(val targetIndex: Int, val ranked: RankedCandidate)
}
