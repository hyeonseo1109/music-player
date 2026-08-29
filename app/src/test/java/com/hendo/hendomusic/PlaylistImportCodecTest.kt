package com.hendo.hendomusic

import com.hendo.hendomusic.library.PlaylistImportCodec
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistImportCodecTest {
    @Test fun readsM3uEntriesAndUsesFileNameAsAlbumName() {
        val playlist = PlaylistImportCodec.parse("Drive.m3u", "#EXTM3U\n#EXTINF:220,Artist - Song\n/storage/emulated/0/Music/Song.mp3\n")
        assertEquals("Drive", playlist.name)
        assertEquals(listOf("Song.mp3"), playlist.entries.map(PlaylistImportCodec::fileName))
    }

    @Test fun readsPlsFileEntriesOnly() {
        val playlist = PlaylistImportCodec.parse("Road.pls", "[playlist]\nFile1=C:\\Music\\One.flac\nTitle1=One\nFile2=/music/Two.mp3\n")
        assertEquals(listOf("One.flac", "Two.mp3"), playlist.entries.map(PlaylistImportCodec::fileName))
    }
}
