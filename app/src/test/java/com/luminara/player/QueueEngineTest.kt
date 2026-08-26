package com.luminara.player

import com.luminara.player.playback.*
import org.junit.Assert.*
import org.junit.Test

class QueueEngineTest {
    private val entries = listOf("A", "B", "C", "D").map { QueueEntry(it, it) }
    @Test fun `reorder preserves current track`() {
        val moved = QueueEngine().move(QueueSnapshot(entries, 1, false, emptyList()), 3, 0)
        assertEquals(listOf("D", "A", "B", "C"), moved.entries.map { it.trackId }); assertEquals("B", moved.entries[moved.currentIndex].trackId)
    }
    @Test fun `play next inserts immediately after current`() {
        val result = QueueEngine().playNext(QueueSnapshot(entries, 1, false, emptyList()), QueueEntry("X", "X"))
        assertEquals("X", result.entries[2].trackId)
    }
    @Test fun `shuffle play next is forced after current in saved sequence`() {
        val shuffled = QueueEngine(4).shuffled(QueueSnapshot(entries, 1, false, emptyList()), true)
        val result = QueueEngine(4).playNext(shuffled, QueueEntry("X", "X"))
        val currentAt = result.shuffleOrder.indexOf(1)
        assertEquals(2, result.shuffleOrder[currentAt + 1]); assertEquals("X", result.entries[2].trackId)
    }
}
