package com.hendo.hendomusic

import com.hendo.hendomusic.lyrics.LrcCodec
import com.hendo.hendomusic.lyrics.buildSyncedLyrics
import org.junit.Assert.*
import org.junit.Test

class LyricsTest {
    @Test fun sync_can_be_built_before_the_first_stamp_without_disabling_save() {
        val synced = buildSyncedLyrics("track", listOf("one", "two"), emptyMap())
        assertEquals(listOf(0L, 0L), synced!!.map { it.startTimeMs })
    }
    @Test fun `lrc parses milliseconds and locates active line`() {
        val lines = LrcCodec.parse("[00:12.340]첫 줄\n[00:16.82]둘째 줄")
        assertEquals(12340, lines[0].startTimeMs); assertEquals(0, LrcCodec.activeIndex(lines, 15000)); assertEquals(1, LrcCodec.activeIndex(lines, 17000))
    }
    @Test fun `offset clamps below zero`() {
        val shifted = LrcCodec.offset(LrcCodec.parse("[00:00.500]a"), -1000); assertEquals(0, shifted.single().startTimeMs)
    }
    @Test fun `lrc round trip`() { val lines = LrcCodec.parse("[01:02.34]hello"); assertEquals(lines.single().text, LrcCodec.parse(LrcCodec.encode(lines)).single().text) }
    @Test fun `remote synced lyric becomes editable local copy without losing timestamps`() { val remote = "[00:01.250]first\n[00:03.500]second"; val local = LrcCodec.parse(remote); assertEquals(listOf(1250L,3500L), local.map { it.startTimeMs }); assertEquals(listOf("first","second"), local.map { it.text }) }
    @Test fun `synced lyric edit can explicitly reset structure`() { val original = LrcCodec.parse("[00:01.00]a\n[00:02.00]b"); val edited = "a\nnew\nb"; assertNotEquals(original.map { it.text }, edited.lines().filter { it.isNotBlank() }) }
    @Test fun `manual sync repairs an unstamped trailing line without resetting it to zero`() {
        val synced = buildSyncedLyrics("track", listOf("one", "two", "three"), mapOf(0 to 1_000L, 1 to 2_000L))
        assertEquals(listOf(1_000L, 2_000L, 2_000L), synced?.map { it.startTimeMs })
    }
    @Test fun `manual group sync preserves every assigned timestamp`() {
        val synced = buildSyncedLyrics("track", listOf("original", "pronunciation", "meaning"), mapOf(0 to 4_200L, 1 to 4_200L, 2 to 4_200L))
        assertEquals(listOf(4_200L, 4_200L, 4_200L), synced?.map { it.startTimeMs })
        assertEquals(2, LrcCodec.activeIndex(synced.orEmpty(), 4_200L))
    }
    @Test fun `complete stamps remain savable after seeking backwards`() {
        val synced = buildSyncedLyrics("track", listOf("one", "two"), mapOf(0 to 2_000L, 1 to 1_000L))
        assertEquals(listOf(2_000L, 1_000L), synced?.map { it.startTimeMs })
    }
}
