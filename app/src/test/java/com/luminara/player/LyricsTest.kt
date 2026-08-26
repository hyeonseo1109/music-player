package com.luminara.player

import com.luminara.player.lyrics.LrcCodec
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun `lrc parses milliseconds and locates active line`() {
        val lines = LrcCodec.parse("[00:12.340]첫 줄\n[00:16.82]둘째 줄")
        assertEquals(12340, lines[0].startTimeMs); assertEquals(0, LrcCodec.activeIndex(lines, 15000)); assertEquals(1, LrcCodec.activeIndex(lines, 17000))
    }
    @Test fun `offset clamps below zero`() {
        val shifted = LrcCodec.offset(LrcCodec.parse("[00:00.500]a"), -1000); assertEquals(0, shifted.single().startTimeMs)
    }
    @Test fun `lrc round trip`() { val lines = LrcCodec.parse("[01:02.34]hello"); assertEquals(lines.single().text, LrcCodec.parse(LrcCodec.encode(lines)).single().text) }
}
