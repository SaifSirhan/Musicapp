package com.sharl.musicapp.data.matching

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CandidateRankerTest {

    private fun target(
        title: String,
        durationMs: Long? = 320_000L,
        artist: String = "Daft Punk",
        trackNumber: Int? = null
    ) = TrackTarget(
        title = title,
        artist = artist,
        durationMs = durationMs,
        albumTitle = "Discovery",
        trackNumber = trackNumber
    )

    private fun candidate(
        id: String,
        title: String,
        durationMs: Long? = 320_000L,
        channel: String = "Daft Punk - Topic",
        trackNumber: Int? = null,
        artist: String? = null
    ) = TrackCandidate(
        videoId = id,
        rawTitle = title,
        channel = channel,
        durationMs = durationMs,
        candidateArtist = artist,
        albumTitle = "Discovery",
        trackNumber = trackNumber
    )

    @Test
    fun exactTitleWithDurationAndTopicIsHighConfidence() {
        val ranked = CandidateRanker.rank(
            target("One More Time", trackNumber = 1),
            listOf(candidate("official", "One More Time", 320_400, trackNumber = 1))
        )!!

        assertEquals(MatchConfidence.HIGH, ranked.confidence)
        assertTrue(ranked.reasons.contains(MatchReason.TITLE_EXACT))
        assertTrue(ranked.reasons.contains(MatchReason.DURATION_MATCH))
        assertTrue(ranked.reasons.contains(MatchReason.ARTIST_MATCH))
        assertTrue(ranked.reasons.contains(MatchReason.TOPIC_CHANNEL))
        assertTrue(ranked.reasons.contains(MatchReason.TRACK_NUMBER_MATCH))
    }

    @Test
    fun durationConflictIsRejected() {
        val ranked = CandidateRanker.rankAll(
            target("One More Time", durationMs = 320_000L),
            listOf(candidate("wrong-length", "One More Time", 200_000L))
        )

        assertTrue(ranked.isEmpty())
    }

    @Test
    fun remixVariantIsNeverAutoSelected() {
        val targets = listOf(target("One More Time"))
        val result = CandidateRanker.assign(
            targets,
            listOf(candidate("remix", "One More Time (Remix)"))
        )

        assertTrue(result.matches.isEmpty())
        assertTrue(result.needsReviewTargets.contains(0))
    }

    @Test
    fun artistConflictPreventsAutoSelection() {
        val result = CandidateRanker.assign(
            listOf(target("One More Time")),
            listOf(
                candidate(
                    "cover",
                    "One More Time",
                    channel = "Some Cover Channel",
                    artist = "Some Other Band"
                )
            )
        )

        assertTrue(result.matches.isEmpty())
        assertTrue(result.needsReviewTargets.contains(0))
    }

    @Test
    fun ambiguousCandidatesNeedReview() {
        val result = CandidateRanker.assign(
            listOf(target("One More Time")),
            listOf(
                candidate("a", "One More Time"),
                candidate("b", "One More Time")
            )
        )

        assertTrue(result.matches.isEmpty())
        assertEquals(listOf(0), result.needsReviewTargets)
    }

    @Test
    fun exactTitleWithoutCorroborationIsNotHigh() {
        val ranked = CandidateRanker.rank(
            TrackTarget(title = "One More Time", durationMs = null),
            listOf(
                TrackCandidate(
                    videoId = "x",
                    rawTitle = "One More Time",
                    channel = "",
                    durationMs = null
                )
            )
        )!!

        assertEquals(MatchConfidence.MEDIUM, ranked.confidence)
    }

    @Test
    fun oneCandidateIsNeverAssignedToTwoTargets() {
        val result = CandidateRanker.assign(
            listOf(
                target("Intro", durationMs = 20_000L, trackNumber = 1),
                target("Intro", durationMs = 20_000L, trackNumber = 2)
            ),
            listOf(candidate("intro", "Intro", durationMs = 20_000L, trackNumber = 1))
        )

        assertEquals(1, result.matches.size)
        assertEquals(1, result.unmatchedTargets.size)
    }

    @Test
    fun albumFixtureRejectsTitleVariantsAndKeepsOfficialOrder() {
        val album = listOf(
            "One More Time" to 320_000L,
            "Aerodynamic" to 212_000L,
            "Digital Love" to 301_000L,
            "Harder, Better, Faster, Stronger" to 224_000L,
            "Crescendolls" to 211_000L,
            "Nightvision" to 104_000L,
            "Superheroes" to 237_000L,
            "High Life" to 202_000L,
            "Something About Us" to 231_000L,
            "Voyager" to 227_000L,
            "Veridis Quo" to 344_000L
        )

        val targets = album.mapIndexed { index, (title, duration) ->
            target(title, durationMs = duration, trackNumber = index + 1)
        }

        val official = album.mapIndexed { index, (title, duration) ->
            candidate(
                id = "official-$index",
                title = title,
                durationMs = duration + 400L,
                trackNumber = index + 1
            )
        }

        val noise = listOf(
            candidate("n0", "One More Time (Official Audio)", 326_000L, channel = "Daft Punk"),
            candidate("n1", "One More Time (Lyrics)", 329_000L, channel = "Lyrics Channel"),
            candidate("n2", "One More Time - Live", 320_000L, channel = "Daft Punk"),
            candidate("n3", "One More Time (Remix)", 320_000L, channel = "Daft Punk"),
            candidate("n4", "Digital Love (Slowed + Reverb)", 301_000L, channel = "Daft Punk"),
            candidate("n5", "Harder, Better, Faster, Stronger (Instrumental)", 224_000L),
            candidate("n6", "Aerodynamic (Cover)", 212_000L, channel = "Cover Crew"),
            candidate("n7", "One More Time (Sped Up)", 160_000L, channel = "Daft Punk"),
            candidate("n8", "Nightvision (Official Audio)", 104_000L, channel = "Daft Punk"),
            candidate("n9", "Veridis Quo (Official Video)", 344_000L, channel = "Daft Punk")
        )

        val result = CandidateRanker.assign(targets, (official + noise).reversed())

        assertEquals("every album track must resolve", album.size, result.matches.size)
        assertTrue(result.needsReviewTargets.isEmpty())
        assertTrue(result.unmatchedTargets.isEmpty())

        val byTarget = result.matches.associateBy { it.targetIndex }
        album.indices.forEach { index ->
            val match = byTarget[index]
            assertEquals("track $index", "official-$index", match?.candidate?.videoId)
            assertEquals(album[index].first, match?.candidate?.rawTitle)
        }
    }

    @Test
    fun assignmentIsDeterministicAcrossCandidateOrderings() {
        val targets = listOf(
            target("One More Time", trackNumber = 1),
            target("Aerodynamic", durationMs = 212_000L, trackNumber = 2)
        )
        val candidates = listOf(
            candidate("one", "One More Time", 320_000L, trackNumber = 1),
            candidate("aero", "Aerodynamic", 212_000L, trackNumber = 2),
            candidate("one-alt", "One More Time (Official Audio)", 326_000L, channel = "Daft Punk")
        )

        val first = CandidateRanker.assign(targets, candidates)
        val second = CandidateRanker.assign(targets, candidates.reversed())

        val firstMap = first.matches.associate { it.targetIndex to it.candidate.videoId }
        val secondMap = second.matches.associate { it.targetIndex to it.candidate.videoId }
        assertEquals(firstMap, secondMap)
        assertEquals("one", firstMap[0])
        assertEquals("aero", firstMap[1])
        assertNotEquals("one-alt", firstMap[0])
    }
}
