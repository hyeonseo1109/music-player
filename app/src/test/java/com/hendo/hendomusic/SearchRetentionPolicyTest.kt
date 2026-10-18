package com.hendo.hendomusic

import com.hendo.hendomusic.ui.shouldClearLibrarySearch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRetentionPolicyTest {
    @Test fun `library child flows retain the current search`() {
        listOf(
            "library",
            "player",
            "queue",
            "albumPicker",
            "metadata/{trackId}",
            "lyricsSearch/{trackId}",
        ).forEach { route -> assertFalse(route, shouldClearLibrarySearch(route)) }
    }

    @Test fun `switching to another top level tab clears the search`() {
        assertTrue(shouldClearLibrarySearch("albums"))
        assertTrue(shouldClearLibrarySearch("settings"))
    }
}
