package com.hendo.hendomusic.metadata

import java.text.Normalizer
import java.util.Locale

/** Conservative matching: automatic results need artist plus a second strong field. */
object MetadataConfidence {
    fun artworkHigh(title: String, artist: String, album: String, candidateTitle: String?, candidateArtist: String?, candidateAlbum: String?): Boolean =
        high(title, artist, album, candidateTitle, candidateArtist, candidateAlbum)

    fun lyricsHigh(title: String, artist: String, candidateTitle: String?, candidateArtist: String?): Boolean {
        val sameTitle = matches(title, candidateTitle)
        val sameArtist = matches(artist, candidateArtist)
        return sameTitle && sameArtist
    }

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

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
        .replace(Regex("(feat\\.?|ft\\.?).*$"), "")
        .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
