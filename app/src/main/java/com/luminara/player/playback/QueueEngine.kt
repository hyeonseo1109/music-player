package com.luminara.player.playback

import kotlin.random.Random

data class QueueEntry(val instanceId: String, val trackId: String, val playNext: Boolean = false)
data class QueueSnapshot(val entries: List<QueueEntry>, val currentIndex: Int, val shuffleEnabled: Boolean, val shuffleOrder: List<Int>)

class QueueEngine(seed: Int = 0) {
    private val random = Random(seed)
    fun playNext(snapshot: QueueSnapshot, entry: QueueEntry): QueueSnapshot {
        val index = (snapshot.currentIndex + 1).coerceAtMost(snapshot.entries.size)
        val updated = snapshot.entries.toMutableList().apply { add(index, entry.copy(playNext = true)) }
        return snapshot.copy(entries = updated, shuffleOrder = remapAfterInsert(snapshot, index))
    }
    fun move(snapshot: QueueSnapshot, from: Int, to: Int): QueueSnapshot {
        if (from !in snapshot.entries.indices || to !in snapshot.entries.indices) return snapshot
        val currentId = snapshot.entries.getOrNull(snapshot.currentIndex)?.instanceId
        val updated = snapshot.entries.toMutableList().apply { add(to, removeAt(from)) }
        return snapshot.copy(entries = updated, currentIndex = updated.indexOfFirst { it.instanceId == currentId }.coerceAtLeast(0), shuffleOrder = emptyList())
    }
    fun shuffled(snapshot: QueueSnapshot, enabled: Boolean): QueueSnapshot {
        if (!enabled) return snapshot.copy(shuffleEnabled = false, shuffleOrder = emptyList())
        val rest = snapshot.entries.indices.filter { it != snapshot.currentIndex }.shuffled(random)
        return snapshot.copy(shuffleEnabled = true, shuffleOrder = listOf(snapshot.currentIndex) + rest)
    }
    private fun remapAfterInsert(snapshot: QueueSnapshot, inserted: Int): List<Int> {
        if (!snapshot.shuffleEnabled) return emptyList()
        val mapped = snapshot.shuffleOrder.map { if (it >= inserted) it + 1 else it }.toMutableList()
        val currentPos = mapped.indexOf(snapshot.currentIndex).takeIf { it >= 0 } ?: 0
        mapped.add((currentPos + 1).coerceAtMost(mapped.size), inserted)
        return mapped
    }
}

object PlayCountPolicy {
    fun qualifies(listenedMs: Long, durationMs: Long): Boolean = listenedMs >= 30_000 || (durationMs > 0 && listenedMs >= durationMs * 0.5)
}
