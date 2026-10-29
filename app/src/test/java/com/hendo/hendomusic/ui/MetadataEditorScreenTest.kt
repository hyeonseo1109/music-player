package com.hendo.hendomusic.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataEditorScreenTest {
    @Test
    fun `artwork search query uses current editor values`() {
        assertEquals(
            "새 가수 새 곡명",
            artworkSearchQuery("  새 곡명 ", " 새 가수  "),
        )
    }

    @Test
    fun `artwork search query omits blank editor values`() {
        assertEquals("새 곡명", artworkSearchQuery("새 곡명", " "))
    }
}
