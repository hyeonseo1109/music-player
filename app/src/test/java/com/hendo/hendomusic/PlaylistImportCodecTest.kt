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

    @Test fun readsSamsungSmplAndKeepsSamsungOrder() {
        val playlist = PlaylistImportCodec.parse(
            "ㅇㅇ.smpl",
            """{"members":[
                {"info":"/storage/emulated/0/노래/굳은살.m4a","order":2},
                {"info":"/storage/emulated/0/노래/걱정의 문득.m4a","order":1},
                {"info":"/storage/emulated/0/노래/그래도.m4a","order":3},
                {"info":"/storage/emulated/0/music song/[Lyrics Video] Choi Yu Ree(최유리) - 감당(Try (Journey Epilogue)).m4a","order":0}
            ],"name":"ㅇㅇ","version":1}""".trimIndent(),
        )

        assertEquals("ㅇㅇ", playlist.name)
        assertEquals(listOf("[Lyrics Video] Choi Yu Ree(최유리) - 감당(Try (Journey Epilogue)).m4a", "걱정의 문득.m4a", "굳은살.m4a", "그래도.m4a"), playlist.entries.map(PlaylistImportCodec::fileName))
    }
}
