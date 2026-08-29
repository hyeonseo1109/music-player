package com.hendo.hendomusic.network

import com.hendo.hendomusic.metadata.MetadataConfidence
import com.squareup.moshi.JsonReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import org.jsoup.Jsoup

data class GenieLyricLine(val startTimeMs: Long, val text: String)
data class GenieLyricsResult(val songId: String, val title: String, val artist: String, val album: String?, val plainText: String, val syncedLines: List<GenieLyricLine>)
internal data class GenieSongCandidate(val songId: String, val title: String, val artist: String, val album: String?)

class GenieLyricsProvider {
    suspend fun search(title: String, artist: String, album: String): GenieLyricsResult? = withContext(Dispatchers.IO) {
        val searchUrl = "https://www.genie.co.kr/search/searchMain".toHttpUrl().newBuilder()
            .addQueryParameter("query", "$artist $title")
            .build()
        val doc = Jsoup.connect(searchUrl.toString())
            // Genie redirects its Android user-agent endpoint to clear-text mobile HTTP.
            // The desktop endpoint is HTTPS and serves the same public search markup.
            .userAgent(desktopUserAgent).timeout(12_000).get()
        val candidate = parseCandidates(doc.outerHtml()).firstOrNull {
            MetadataConfidence.genieLyricsHigh(title, artist, it.title, it.artist)
        } ?: return@withContext null
        val lyricUrl = "https://dn.genie.co.kr/app/purchase/get_msl.asp".toHttpUrl().newBuilder()
            .addQueryParameter("songid", candidate.songId)
            .build()
        val lines = parseLines(Jsoup.connect(lyricUrl.toString())
            .userAgent(desktopUserAgent).timeout(12_000).ignoreContentType(true).execute().body())
        if (lines.isEmpty()) return@withContext null
        GenieLyricsResult(candidate.songId, candidate.title, candidate.artist, candidate.album, lines.joinToString("\n") { it.text }, lines)
    }

    companion object {
        private const val desktopUserAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        /** Restrict matching to Genie song rows so lyric snippets cannot be selected as songs. */
        internal fun parseCandidates(html: String): List<GenieSongCandidate> = Jsoup.parse(html)
            .select("div.search_song tr.list")
            .mapNotNull { row ->
                row.attr("songid").takeIf { it.isNotBlank() }?.let { songId ->
                    GenieSongCandidate(
                        songId = songId,
                        title = row.selectFirst("a.title")?.text().orEmpty(),
                        artist = row.selectFirst("a.artist")?.text().orEmpty(),
                        album = row.selectFirst("a.albumtitle")?.text(),
                    )
                }
            }

        fun parseLines(response: String): List<GenieLyricLine> = runCatching {
            val start = response.indexOf('('); val end = response.lastIndexOf(')')
            if (start < 0 || end <= start) return emptyList()
            val reader = JsonReader.of(Buffer().writeUtf8(response.substring(start + 1, end)))
            buildList {
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    val text = if (reader.peek() == JsonReader.Token.STRING) reader.nextString() else { reader.skipValue(); "" }
                    key.toLongOrNull()?.let { ms ->
                        text.replace("&nbsp;", " ").trim().takeIf { it.isNotBlank() }?.let { add(GenieLyricLine(ms, it)) }
                    }
                }
                reader.endObject()
            }.sortedBy { it.startTimeMs }
        }.getOrDefault(emptyList())
    }
}
