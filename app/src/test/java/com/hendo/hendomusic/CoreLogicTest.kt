package com.hendo.hendomusic

import com.hendo.hendomusic.domain.*
import com.hendo.hendomusic.playback.*
import com.hendo.hendomusic.data.TrackEntity
import com.hendo.hendomusic.library.mergeScannedTrack
import org.junit.Assert.*
import org.junit.Test

class CoreLogicTest {
    @Test fun `play count threshold`() { assertFalse(PlayCountPolicy.qualifies(20_000, 100_000)); assertTrue(PlayCountPolicy.qualifies(30_000, 100_000)); assertTrue(PlayCountPolicy.qualifies(25_000, 40_000)) }
    @Test fun `rescan diff`() { val diff = scanDiff(setOf("a", "b"), setOf("b", "c")); assertEquals(setOf("c"), diff.added); assertEquals(setOf("a"), diff.removed) }
    @Test fun `repeat cycles all modes`() { assertEquals(1, RepeatCycle.next(0)); assertEquals(2, RepeatCycle.next(1)); assertEquals(0, RepeatCycle.next(2)) }
    @Test fun `album folder move persists normalized order`() { val result = moveAlbum(listOf(AlbumLocation(1L,null,0), AlbumLocation(2L,4L,0)), 1L, 4L, 1); assertEquals(4L, result.first{it.albumId==1L}.folderId); assertEquals(1, result.first{it.albumId==1L}.order) }
    @Test fun `album reorder persists normalized order`() { val result = moveAlbum(listOf(AlbumLocation(1,null,0), AlbumLocation(2,null,1), AlbumLocation(3,null,2)), 3, null, 0); assertEquals(listOf(3L,1L,2L), result.sortedBy { it.order }.map { it.albumId }) }
    @Test fun `folder create and dissolve preserves albums`() {
        val initial = AlbumFolderState(emptySet(), listOf(AlbumLocation(1,null,0), AlbumLocation(2,null,1), AlbumLocation(3,null,2)))
        val folder = AlbumFolderEngine.create(initial, 9, 1, 2)
        assertEquals(listOf(1L,2L), folder.albums.filter { it.folderId == 9L }.sortedBy { it.order }.map { it.albumId })
        val dissolved = AlbumFolderEngine.dissolve(folder, 9)
        assertTrue(9L !in dissolved.folders); assertEquals(setOf(1L,2L,3L), dissolved.albums.filter { it.folderId == null }.map { it.albumId }.toSet())
    }
    @Test fun `vote duplicate guard allows retry only after failure`() { val guard = VoteDuplicateGuard(); assertTrue(guard.begin("l")); assertFalse(guard.begin("l")); guard.failed("l"); assertTrue(guard.begin("l")) }
    @Test fun `rescan refreshes media metadata but preserves user state`() {
        fun track(title: String) = TrackEntity("1", 1, "content://song/1", null, "a.mp3", title, "artist", "album", null, 1_000, 1, 1, null)
        val old = track("old").copy(isFavorite = true, playCount = 7, lastPlayedAt = 99, customArtworkUri = "file://art", createdAt = 12)
        val merged = mergeScannedTrack(track("fresh"), old)
        assertEquals("fresh", merged.title); assertTrue(merged.isFavorite); assertEquals(7, merged.playCount); assertEquals("file://art", merged.customArtworkUri); assertEquals(12, merged.createdAt)
    }
    @Test fun `restore keeps queue order position shuffle and skips missing`() {
        val entries = listOf("A","B","C","D").map { QueueEntry(it,it) }
        val restored = QueueRestorer.restore(QueueSnapshot(entries,2,true,listOf(2,0,3,1)), setOf("A","C","D"), "C", 102_000)
        assertEquals(listOf("A","C","D"), restored.entries.map{it.trackId}); assertEquals(1, restored.currentIndex); assertEquals(102_000, restored.positionMs); assertEquals(listOf(1,0,2), restored.shuffleOrder)
    }
    @Test fun `restore keeps exact queue instance when track is duplicated`() {
        val duplicate = listOf(QueueEntry("a1", "A"), QueueEntry("b", "B"), QueueEntry("a2", "A"))
        val restored = QueueRestorer.restore(QueueSnapshot(duplicate, 2, false, emptyList()), setOf("A", "B"), "A", 4_000)
        assertEquals(2, restored.currentIndex); assertEquals("a2", restored.entries[restored.currentIndex].instanceId)
    }
}
