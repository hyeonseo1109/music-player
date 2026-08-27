package com.luminara.player.domain

import com.luminara.player.playback.QueueEntry
import com.luminara.player.playback.QueueSnapshot

data class ScanDiff<T>(val added: Set<T>, val removed: Set<T>, val retained: Set<T>)
fun <T> scanDiff(previous: Set<T>, current: Set<T>) = ScanDiff(current - previous, previous - current, previous intersect current)

data class RestoredQueue(val entries: List<QueueEntry>, val currentIndex: Int, val positionMs: Long, val shuffleOrder: List<Int>)
object QueueRestorer {
    fun restore(snapshot: QueueSnapshot, availableTrackIds: Set<String>, currentTrackId: String?, positionMs: Long): RestoredQueue {
        val valid = snapshot.entries.filter { it.trackId in availableTrackIds }
        val current = valid.indexOfFirst { it.trackId == currentTrackId }.let { if (it >= 0) it else 0 }
        val oldById = snapshot.entries.withIndex().associate { it.value.instanceId to it.index }
        val newByOld = valid.withIndex().associate { oldById[it.value.instanceId] to it.index }
        val shuffle = snapshot.shuffleOrder.mapNotNull(newByOld::get).distinct()
        return RestoredQueue(valid, current.coerceAtMost((valid.size - 1).coerceAtLeast(0)), positionMs.coerceAtLeast(0), shuffle)
    }
}

object RepeatCycle { fun next(mode: Int): Int = (mode + 1) % 3 }

data class AlbumLocation(val albumId: Long, val folderId: Long?, val order: Int)
fun moveAlbum(items: List<AlbumLocation>, albumId: Long, folderId: Long?, newOrder: Int): List<AlbumLocation> {
    val moved = items.firstOrNull { it.albumId == albumId } ?: return items
    val without = items.filterNot { it.albumId == albumId }.toMutableList()
    val targetIndices = without.withIndex().filter { it.value.folderId == folderId }.map { it.index }
    val insertion = if (targetIndices.isEmpty()) without.size else (targetIndices.first() + newOrder).coerceAtMost(targetIndices.last() + 1)
    without.add(insertion, moved.copy(folderId = folderId))
    return without.groupBy { it.folderId }.flatMap { (_, albums) -> albums.mapIndexed { i, a -> a.copy(order = i) } }
}

data class AlbumFolderState(val folders: Set<Long>, val albums: List<AlbumLocation>)
object AlbumFolderEngine {
    fun create(state: AlbumFolderState, folderId: Long, first: Long, second: Long): AlbumFolderState = state.copy(
        folders = state.folders + folderId,
        albums = moveAlbum(moveAlbum(state.albums, first, folderId, 0), second, folderId, 1),
    )
    fun dissolve(state: AlbumFolderState, folderId: Long): AlbumFolderState {
        val rootSize = state.albums.count { it.folderId == null }
        val members = state.albums.filter { it.folderId == folderId }.sortedBy { it.order }
        val moved = members.foldIndexed(state.albums) { index, albums, member -> moveAlbum(albums, member.albumId, null, rootSize + index) }
        return AlbumFolderState(state.folders - folderId, moved)
    }
}

/** Fast local guard; the database primary key remains the cross-device authority. */
class VoteDuplicateGuard {
    private val pendingOrCompleted = mutableSetOf<String>()
    fun begin(lyricsId: String): Boolean = pendingOrCompleted.add(lyricsId)
    fun failed(lyricsId: String) { pendingOrCompleted.remove(lyricsId) }
}
