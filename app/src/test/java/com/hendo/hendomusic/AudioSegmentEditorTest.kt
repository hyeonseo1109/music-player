package com.hendo.hendomusic

import com.hendo.hendomusic.library.AudioEditMode
import com.hendo.hendomusic.library.editSegments
import com.hendo.hendomusic.library.editedAudioName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSegmentEditorTest {
    @Test fun `keep emits only selected range`() {
        assertEquals(listOf(10_000_000L to 20_000_000L), editSegments(60_000, 10_000, 20_000, AudioEditMode.KEEP_SELECTION).map { it.startUs to it.endUs })
    }

    @Test fun `remove joins front and back ranges`() {
        assertEquals(listOf(0L to 10_000_000L, 20_000_000L to 60_000_000L), editSegments(60_000, 10_000, 20_000, AudioEditMode.REMOVE_SELECTION).map { it.startUs to it.endUs })
    }

    @Test fun `remove supports trimming either edge`() {
        assertEquals(listOf(20_000_000L to 60_000_000L), editSegments(60_000, 0, 20_000, AudioEditMode.REMOVE_SELECTION).map { it.startUs to it.endUs })
        assertEquals(listOf(0L to 10_000_000L), editSegments(60_000, 10_000, 60_000, AudioEditMode.REMOVE_SELECTION).map { it.startUs to it.endUs })
    }

    @Test fun `edited file receives unique m4a suffix`() {
        val name = editedAudioName("song.mp3", 0)
        assertTrue(name.startsWith("song_edited_"))
        assertTrue(name.endsWith(".m4a"))
    }
}
