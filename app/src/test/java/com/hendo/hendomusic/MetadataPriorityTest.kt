package com.hendo.hendomusic

import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.data.ArtworkSource
import com.hendo.hendomusic.data.displayArtworkUri
import com.hendo.hendomusic.data.isMissingAlbumName
import com.hendo.hendomusic.metadata.MetadataConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataPriorityTest {
    private fun track(custom: String? = null, embedded: String? = null, auto: String? = null, album: String = "Album") = TrackEntity(
        id = "t", mediaStoreId = null, uri = "content://t", relativePath = null, fileName = "t.mp3",
        title = "Song", artist = "Artist", album = album, albumArtist = null, durationMs = 1,
        dateAdded = 0, dateModified = 0, albumArtUri = embedded, customArtworkUri = custom,
        customArtworkSource = custom?.let { ArtworkSource.USER.name }, autoArtworkUri = auto,
    )

    @Test fun `artwork priority is user then embedded then auto`() {
        assertEquals("user", track("user", "embedded", "auto").displayArtworkUri())
        assertEquals("embedded", track(embedded = "embedded", auto = "auto").displayArtworkUri())
        assertEquals("auto", track(auto = "auto").displayArtworkUri())
    }

    @Test fun `tracks without albums never share album scoped artwork`() {
        listOf("", "   ", "<unknown>", "UNKNOWN", "Unknown Album", "Unknown Album (2020)", "알 수 없는 앨범", "알 수 없는 앨범 (2020-02-16 오후 2:56:55)").forEach { album ->
            val track = track(custom = null, embedded = "content://shared/unknown", auto = "https://auto", album = album)
            assertTrue(album.isMissingAlbumName())
            assertEquals(null, track.displayArtworkUri())
        }
    }

    @Test fun `non user legacy artwork is ignored when album is missing`() {
        val track = track(custom = null, album = "알 수 없는 앨범 (2020)").copy(
            customArtworkUri = "content://shared/legacy",
            customArtworkSource = null,
        )
        assertEquals(null, track.displayArtworkUri())
    }

    @Test fun `explicit user artwork remains track specific without an album`() {
        val track = track(custom = "file://user", embedded = "content://shared/unknown", auto = "https://auto", album = "")
        assertEquals("file://user", track.displayArtworkUri())
    }

    @Test fun `known album keeps embedded and automatic priority`() {
        assertEquals("content://embedded", track(custom = null, embedded = "content://embedded", auto = "https://auto", album = "Album").displayArtworkUri())
        assertEquals("https://auto", track(custom = null, embedded = null, auto = "https://auto", album = "Album").displayArtworkUri())
    }

    @Test fun `auto confidence requires artist and matching title or album`() {
        assertTrue(MetadataConfidence.artworkHigh("Song", "Artist", "Album", "Song", "Artist", "Album"))
        assertFalse(MetadataConfidence.artworkHigh("Song", "Artist", "Album", "Song", "Other", "Album"))
        assertTrue(MetadataConfidence.lyricsHigh("Song", "Artist", 200_000, "Song", "Artist", 204_000))
        assertFalse(MetadataConfidence.lyricsHigh("Song", "Artist", 200_000, "Song", "Other", 200_000))
        assertFalse(MetadataConfidence.lyricsHigh("Song", "Artist", 200_000, "Song", "Artist", 220_000))
    }
}
