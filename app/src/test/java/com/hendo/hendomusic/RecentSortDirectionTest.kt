package com.hendo.hendomusic

import com.hendo.hendomusic.data.TrackEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentSortDirectionTest {
    @Test fun `recent direction supports newest and oldest first`() {
        val tracks = listOf(track("middle", 20), track("oldest", 10), track("newest", 30))

        assertEquals(listOf("newest", "middle", "oldest"), sortByDateAdded(tracks, false).map { it.id })
        assertEquals(listOf("oldest", "middle", "newest"), sortByDateAdded(tracks, true).map { it.id })
    }

    private fun track(id: String, added: Long) = TrackEntity(
        id = id,
        mediaStoreId = null,
        uri = "content://test/$id",
        relativePath = null,
        fileName = "$id.mp3",
        title = id,
        artist = "artist",
        album = "album",
        albumArtist = null,
        durationMs = 1,
        dateAdded = added,
        dateModified = added,
        albumArtUri = null,
    )
}
