package com.hendo.hendomusic

import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.data.displayArtworkUri
import com.hendo.hendomusic.metadata.MetadataConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataPriorityTest {
    private fun track(custom: String? = null, embedded: String? = null, auto: String? = null) = TrackEntity(
        id = "t", mediaStoreId = null, uri = "content://t", relativePath = null, fileName = "t.mp3",
        title = "Song", artist = "Artist", album = "Album", albumArtist = null, durationMs = 1,
        dateAdded = 0, dateModified = 0, albumArtUri = embedded, customArtworkUri = custom, autoArtworkUri = auto,
    )

    @Test fun `artwork priority is user then embedded then auto`() {
        assertEquals("user", track("user", "embedded", "auto").displayArtworkUri())
        assertEquals("embedded", track(embedded = "embedded", auto = "auto").displayArtworkUri())
        assertEquals("auto", track(auto = "auto").displayArtworkUri())
    }

    @Test fun `auto confidence requires artist and matching title or album`() {
        assertTrue(MetadataConfidence.artworkHigh("Song", "Artist", "Album", "Song", "Artist", "Album"))
        assertFalse(MetadataConfidence.artworkHigh("Song", "Artist", "Album", "Song", "Other", "Album"))
        assertTrue(MetadataConfidence.lyricsHigh("Song", "Artist", 200_000, "Song", "Artist", 204_000))
        assertFalse(MetadataConfidence.lyricsHigh("Song", "Artist", 200_000, "Song", "Other", 200_000))
        assertFalse(MetadataConfidence.lyricsHigh("Song", "Artist", 200_000, "Song", "Artist", 220_000))
    }
}
