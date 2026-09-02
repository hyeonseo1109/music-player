package com.hendo.hendomusic.metadata

import java.text.Normalizer
import java.util.Locale

/** Conservative matching: automatic results need artist plus a second strong field. */
object MetadataConfidence {
    fun artworkHigh(title: String, artist: String, album: String, candidateTitle: String?, candidateArtist: String?, candidateAlbum: String?): Boolean =
        high(title, artist, album, candidateTitle, candidateArtist, candidateAlbum)

    fun lyricsHigh(
        title: String,
        artist: String,
        durationMs: Long,
        candidateTitle: String?,
        candidateArtist: String?,
        candidateDurationMs: Long?,
    ): Boolean {
        val sameTitle = matches(title, candidateTitle)
        val sameArtist = matches(artist, candidateArtist)
        // Keep duration conservative when the provider supplies it; Genie results have no
        // reliable duration and take the explicit fallback path below.
        val closeDuration = candidateDurationMs == null || durationMs <= 0L || kotlin.math.abs(durationMs - candidateDurationMs) <= 8_000L
        return sameTitle && sameArtist && closeDuration
    }

    /** Genie has no reliable duration in search results. Parenthetical aliases and feat tags
     * are removed by normalize(), after which both title and artist still need to match. */
    fun genieLyricsHigh(title: String, artist: String, candidateTitle: String?, candidateArtist: String?): Boolean =
        strictMatches(title, candidateTitle) && strictMatches(artist, candidateArtist)

    private fun high(title: String, artist: String, album: String, candidateTitle: String?, candidateArtist: String?, candidateAlbum: String?): Boolean {
        val sameArtist = matches(artist, candidateArtist)
        val sameAlbum = matches(album, candidateAlbum)
        val sameTitle = matches(title, candidateTitle)
        return sameArtist && (sameAlbum || sameTitle)
    }

    private fun matches(expected: String, actual: String?): Boolean {
        val left = normalize(expected)
        val right = normalize(actual.orEmpty())
        return left.length >= 2 && right.length >= 2 && (left == right || left.contains(right) || right.contains(left))
    }

    private fun strictMatches(expected: String, actual: String?): Boolean {
        val left = normalize(expected)
        val right = normalize(actual.orEmpty())
        return left.length >= 2 && right.length >= 2 && left == right
    }

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\(\\[（][^\\)\\]）]*[\\)\\]）]"), "")
        .replace(Regex("(feat\\.?|ft\\.?).*$"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
