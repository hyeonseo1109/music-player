package com.hendo.hendomusic

import com.hendo.hendomusic.metadata.MetadataConfidence
import com.hendo.hendomusic.network.GenieLyricsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenieLyricsProviderTest {
    @Test fun `parses Genie song rows without accepting lyric snippets`() {
        val html = """
            <div class="search_lyrics"><tr class="list" songid="wrong"><a class="title">snippet</a></tr></div>
            <div class="search_song"><table>
              <tr class="list" songid="123"><td><a class="title">Creep</a><a class="artist">Radiohead</a><a class="albumtitle">Pablo Honey</a></td></tr>
            </table></div>
        """.trimIndent()

        val candidates = GenieLyricsProvider.parseCandidates(html)

        assertEquals(1, candidates.size)
        assertEquals("123", candidates.single().songId)
        assertEquals("Creep", candidates.single().title)
    }

    @Test fun `parses and sorts timestamp keyed Genie payload`() {
        val lines = GenieLyricsProvider.parseLines("callback({\"1200\":\" second \",\"300\":\"first\",\"900\":\" &nbsp; \"})")

        assertEquals(listOf(300L, 1200L), lines.map { it.startTimeMs })
        assertEquals(listOf("first", "second"), lines.map { it.text })
    }

    @Test fun `rejects malformed payload and loose artist matches`() {
        assertTrue(GenieLyricsProvider.parseLines("not json").isEmpty())
        assertTrue(MetadataConfidence.genieLyricsHigh("윤마치", "윤마치", "윤마치 (MRCH)", "윤마치"))
        assertFalse(MetadataConfidence.genieLyricsHigh("Hello", "Artist", "Hello world", "Artist"))
    }
}
