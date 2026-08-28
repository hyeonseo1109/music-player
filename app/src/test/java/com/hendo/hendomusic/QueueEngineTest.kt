package com.hendo.hendomusic

import com.hendo.hendomusic.playback.*
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
    @Test fun `play next then reorder persists queue current and position`() {
        val engine = QueueEngine(7)
        val withNext = engine.playNext(QueueSnapshot(entries, 2, false, emptyList()), QueueEntry("X", "X"))
        val moved = engine.move(withNext, 4, 1)
        val restored = com.hendo.hendomusic.domain.QueueRestorer.restore(moved, moved.entries.map { it.trackId }.toSet(), "C", 91_234)
        assertEquals(listOf("A", "D", "B", "C", "X"), restored.entries.map { it.trackId })
        assertEquals("C", restored.entries[restored.currentIndex].trackId)
        assertEquals(91_234, restored.positionMs)
    }
    @Test fun `reorder remaps shuffle without losing traversal`() {
        val shuffled = QueueEngine(5).shuffled(QueueSnapshot(entries, 2, false, emptyList()), true)
        val before = shuffled.shuffleOrder.map { shuffled.entries[it].instanceId }
        val moved = QueueEngine(5).move(shuffled, 3, 0)
        assertEquals(before, moved.shuffleOrder.map { moved.entries[it].instanceId })
    }
}
