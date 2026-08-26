package com.luminara.player

import com.luminara.player.domain.*
import com.luminara.player.playback.*
import org.junit.Assert.*
import org.junit.Test

class CoreLogicTest {
    @Test fun `play count threshold`() { assertFalse(PlayCountPolicy.qualifies(20_000, 100_000)); assertTrue(PlayCountPolicy.qualifies(30_000, 100_000)); assertTrue(PlayCountPolicy.qualifies(25_000, 40_000)) }
    @Test fun `rescan diff`() { val diff = scanDiff(setOf("a", "b"), setOf("b", "c")); assertEquals(setOf("c"), diff.added); assertEquals(setOf("a"), diff.removed) }
    @Test fun `repeat cycles all modes`() { assertEquals(1, RepeatCycle.next(0)); assertEquals(2, RepeatCycle.next(1)); assertEquals(0, RepeatCycle.next(2)) }
    @Test fun `album folder move persists normalized order`() { val result = moveAlbum(listOf(AlbumLocation(1L,null,0), AlbumLocation(2L,4L,0)), 1L, 4L, 1); assertEquals(4L, result.first{it.albumId==1L}.folderId); assertEquals(1, result.first{it.albumId==1L}.order) }
    @Test fun `restore keeps queue order position shuffle and skips missing`() {
        val entries = listOf("A","B","C","D").map { QueueEntry(it,it) }
        val restored = QueueRestorer.restore(QueueSnapshot(entries,2,true,listOf(2,0,3,1)), setOf("A","C","D"), "C", 102_000)
        assertEquals(listOf("A","C","D"), restored.entries.map{it.trackId}); assertEquals(1, restored.currentIndex); assertEquals(102_000, restored.positionMs); assertEquals(listOf(1,0,2), restored.shuffleOrder)
    }
}
